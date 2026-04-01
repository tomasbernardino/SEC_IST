package ist.group29.depchain.server.service;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.fluent.EVMExecutor;
import org.hyperledger.besu.evm.fluent.SimpleWorld;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

import com.google.protobuf.ByteString;

import ist.group29.depchain.client.ClientMessages.Block;
import ist.group29.depchain.client.ClientMessages.Transaction;
import ist.group29.depchain.client.ClientMessages.TransactionResponse;
import ist.group29.depchain.client.ClientMessages.TransactionStatus;
import ist.group29.depchain.common.crypto.CryptoUtils;
import ist.group29.depchain.server.service.account.BlockchainAccount;
import ist.group29.depchain.server.service.account.ContractAccount;
import ist.group29.depchain.server.service.account.EOA;

/**
 * Executes transactions deterministically and charges the sender in native
 * DepCoin for the gas actually consumed.
 */
public class TransactionExecutor {
    private static final Logger LOG = Logger.getLogger(TransactionExecutor.class.getName());

    private static final long NATIVE_TRANSFER_GAS = 21000;
    private static final long CONTRACT_CREATION_GAS = 32000;

    // Solidity's standard revert payloads start with a 4-byte selector identifying
    // the ABI-encoded error type.
    private static final String ERROR_STRING_SELECTOR = "0x08c379a0";

    private final BlockchainState state;

    public TransactionExecutor(BlockchainState state) {
        this.state = state;
    }

    /**
     * Executes all transactions in a block sequentially.
     */
    public java.util.List<TransactionResponse> executeBlock(Block block, long blockNumber) {
        LOG.info("[Executor] Executing block #{" + blockNumber + "} with { " + block.getTransactionsList().size() + " } transactions");
        java.util.List<TransactionResponse> results = new java.util.ArrayList<>();
        for (Transaction tx : block.getTransactionsList()) {
            results.add(execute(tx, blockNumber));
        }
        return results;
    }

    /**
     * Executes a single transaction against the current state.
     */
    public TransactionResponse execute(Transaction tx, long blockNumber) {

        LOG.info("[Executor] Processing tx from " + tx.getFrom() + " to " + tx.getTo() + " nonce=" + tx.getNonce());

        // Normalize addresses (strip 0x prefix, lowercase) to match state keys
        String fromAddr = CryptoUtils.normalizeAddress(tx.getFrom());

        // Pre-execution validation
        BlockchainAccount senderAcc = state.getAccount(fromAddr);
        if (!(senderAcc instanceof EOA sender)) {
            return errorResponse(tx, TransactionStatus.INVALID_SIGNATURE, "Sender must be an EOA.");
        }

        if (tx.getNonce() != sender.getNonce()) {
            return errorResponse(tx, TransactionStatus.INVALID_NONCE,
                    "Nonce mismatch: expected " + sender.getNonce() + ", got " + tx.getNonce());
        }

        BigInteger maxGasFee = BigInteger.valueOf(tx.getGasLimit()).multiply(BigInteger.valueOf(tx.getGasPrice()));
        BigInteger value = BigInteger.valueOf(tx.getValue());
        BigInteger totalRequired = maxGasFee.add(value);

        if (sender.getBalance().compareTo(totalRequired) < 0) {
            return errorResponse(tx, TransactionStatus.INSUFFICIENT_FUNDS,
                    "Account " + tx.getFrom() + " cannot afford gas_limit * gas_price + value.");
        }

        // Branch execution based on calldata and destination
        if (tx.getData().isEmpty()) {
            if (tx.getTo().isEmpty()) {
                return errorResponse(tx, TransactionStatus.FAILURE, "Native transfer must have a destination address.");
            }
            return executeNativeTransfer(tx, sender, blockNumber);
        }

        if (tx.getTo().isEmpty()) {
            return executeContractCreation(tx, sender, blockNumber);
        }
        return executeContractCall(tx, sender, blockNumber);
    }

    private TransactionResponse executeNativeTransfer(Transaction tx, EOA sender, long blockNumber) {
        long gasUsed = NATIVE_TRANSFER_GAS;

        if (gasUsed > tx.getGasLimit()) {
            chargeGas(sender, tx.getGasLimit(), tx.getGasPrice());
            sender.incrementNonce();
            return successResponse(tx, TransactionStatus.OUT_OF_GAS, tx.getGasLimit(), blockNumber, Bytes.EMPTY,
                    "Exceeded gas limit.");
        }

        chargeGas(sender, gasUsed, tx.getGasPrice());

        String toAddr = CryptoUtils.normalizeAddress(tx.getTo());
        BlockchainAccount receiverAcc = state.getOrCreateAccount(toAddr);
        sender.debit(BigInteger.valueOf(tx.getValue()));
        receiverAcc.credit(BigInteger.valueOf(tx.getValue()));

        sender.incrementNonce();
        return successResponse(tx, TransactionStatus.SUCCESS, gasUsed, blockNumber, Bytes.EMPTY, "Transfer complete.");
    }

    private TransactionResponse executeContractCall(Transaction tx, EOA sender, long blockNumber) {
        String toAddr = CryptoUtils.normalizeAddress(tx.getTo());
        BlockchainAccount receiverAcc = state.getAccount(toAddr);
        if (!(receiverAcc instanceof ContractAccount contractAcc)) {
            return errorResponse(tx, TransactionStatus.FAILURE, "Destination is not a contract account.");
        }

        String codeHex = contractAcc.getCode();
        if (codeHex == null || codeHex.isBlank() || codeHex.equals("0x")) {
            return errorResponse(tx, TransactionStatus.FAILURE, "Destination contract has no code.");
        }

        return executeEvmTransaction(
                tx,
                sender,
                blockNumber,
                Optional.of(toBesuAddress(tx.getTo())),
                Bytes.fromHexString(codeHex),
                Bytes.wrap(tx.getData().toByteArray()),
                "Execution succeeded.");
    }

    private TransactionResponse executeContractCreation(Transaction tx, EOA sender, long blockNumber) {
        return executeEvmTransaction(
                tx,
                sender,
                blockNumber,
                Optional.empty(),
                Bytes.wrap(tx.getData().toByteArray()),
                Bytes.EMPTY,
                "Contract deployed successfully.");
    }

    private TransactionResponse executeEvmTransaction(
            Transaction tx,
            EOA sender,
            long blockNumber,
            Optional<Address> receiver,
            Bytes code,
            Bytes callData,
            String successMessage) {

        /*  Besu's executor meters only the bytecode execution itself. The fixed
            intrinsic gas is the transaction overhead (base tx cost, creation surcharge, calldata bytes)
            which is charged here first so we can reject transactions without the necessary gas before entering the EVM. */
        
        long intrinsicGas = calculateIntrinsicGas(tx);
        if (intrinsicGas > tx.getGasLimit()) {
            chargeGas(sender, tx.getGasLimit(), tx.getGasPrice());
            sender.incrementNonce();
            return successResponse(tx, TransactionStatus.OUT_OF_GAS, tx.getGasLimit(), blockNumber, Bytes.EMPTY,
                    "Intrinsic gas exceeds limit.");
        }

        SimpleWorld besuWorld = new SimpleWorld();
        bridgeStateToBesu(state, besuWorld);
        WorldUpdater updater = besuWorld.updater();

        ExecutionTracer tracer = new ExecutionTracer();
        EVMExecutor evmExecutor = EVMExecutor.evm(EvmSpecVersion.CANCUN);
        evmExecutor.worldUpdater(updater);
        evmExecutor.tracer(tracer);
        evmExecutor.sender(toBesuAddress(sender.getAddress()));
        receiver.ifPresent(evmExecutor::receiver);
        evmExecutor.code(code);
        evmExecutor.callData(callData);
        evmExecutor.ethValue(Wei.of(tx.getValue()));
        // Only the remainder gas is available to bytecode execution.
        evmExecutor.gas(tx.getGasLimit() - intrinsicGas);

        try {
            Bytes rawOutput = evmExecutor.execute();
            Bytes output = tracer.resolveOutput(rawOutput);
            // Besu reports the gas left after execution, so we reconstruct the
            // dynamic execution cost from the execution budget we handed to it.
            long gasUsedByEvm = Math.max(0, (tx.getGasLimit() - intrinsicGas) - tracer.getRemainingGas());
            long totalGasUsed = intrinsicGas + gasUsedByEvm;

            TransactionStatus status = tracer.failed() ? TransactionStatus.FAILURE : TransactionStatus.SUCCESS;
            String message = tracer.failed() ? tracer.describeFailure(output) : successMessage;

            if (!tracer.failed()) {
                updater.commit();
                commitBesuChanges(besuWorld, state);
            }

            chargeGas(sender, totalGasUsed, tx.getGasPrice());
            sender.incrementNonce();

            LOG.info("[Executor] EVM execution " + (tracer.failed() ? "FAILED" : "SUCCEEDED")
                    + ". Gas used: " + totalGasUsed + "/" + tx.getGasLimit());
            return successResponse(tx, status, totalGasUsed, blockNumber, output, message);
        } catch (Exception e) {
            LOG.severe("[Executor] EVM execution failed critically: " + e.getMessage());
            chargeGas(sender, tx.getGasLimit(), tx.getGasPrice());
            sender.incrementNonce();
            return errorResponse(tx, TransactionStatus.FAILURE, "EVM Error: " + e.getMessage());
        }
    }

    private void chargeGas(EOA account, long gasUsed, long gasPrice) {
        BigInteger fee = BigInteger.valueOf(gasUsed).multiply(BigInteger.valueOf(gasPrice));
        account.debit(fee);
    }

    private void bridgeStateToBesu(BlockchainState source, SimpleWorld target) {
        for (BlockchainAccount acc : source.getAllAccounts()) {
            Address addr = toBesuAddress(acc.getAddress());
            long nonce = (acc instanceof EOA eoa) ? eoa.getNonce() : 0;
            target.createAccount(addr, nonce, Wei.of(acc.getBalance()));

            MutableAccount mutable = (MutableAccount) target.get(addr);
            if (acc.isContract()) {
                ContractAccount ca = (ContractAccount) acc;
                // Rebuild the contract account inside Besu so the EVM sees the
                // same bytecode and storage layout as our persisted world state.
                mutable.setCode(Bytes.fromHexString(ca.getCode()));
                for (Map.Entry<String, String> slot : ca.getStorage().entrySet()) {
                    String key = slot.getKey().startsWith("0x") ? slot.getKey() : "0x" + slot.getKey();
                    String value = slot.getValue().startsWith("0x") ? slot.getValue() : "0x" + slot.getValue();
                    mutable.setStorageValue(UInt256.fromHexString(key), UInt256.fromHexString(value));
                }
            }
        }
    }

    private void commitBesuChanges(SimpleWorld besuWorld, BlockchainState destination) {
        for (org.hyperledger.besu.evm.account.Account besuAcc : besuWorld.getTouchedAccounts()) {
            String addr = CryptoUtils.normalizeAddress(besuAcc.getAddress().toHexString());
            BlockchainAccount ourAcc = destination.getOrCreateAccount(addr);

            ourAcc.setBalance(besuAcc.getBalance().toBigInteger());
            if (ourAcc instanceof EOA eoa) {
                eoa.setNonce(besuAcc.getNonce());
            }

            boolean hasCode = besuAcc.getCode() != null && !besuAcc.getCode().isEmpty();
            if (hasCode || ourAcc instanceof ContractAccount) {
                ContractAccount contractAcc;
                if (ourAcc instanceof ContractAccount existingContract) {
                    contractAcc = existingContract;
                } else {
                    /* Contract creation touches an address that may not exist yet
                       in our state map, so we create a contract account before
                       copying code/storage back.*/
                    contractAcc = new ContractAccount(addr);
                    contractAcc.setBalance(ourAcc.getBalance());
                    destination.addAccount(contractAcc);
                }

                if (hasCode) {
                    contractAcc.setCode(besuAcc.getCode().toHexString());
                }

                if (besuAcc instanceof org.hyperledger.besu.evm.account.MutableAccount mutable) {
                    for (Map.Entry<UInt256, UInt256> slot : mutable.getUpdatedStorage().entrySet()) {
                        String keyHex = String.format("0x%064x", slot.getKey().toBigInteger());
                        String valueHex = String.format("0x%064x", slot.getValue().toBigInteger());
                        contractAcc.getStorage().put(keyHex, valueHex);
                    }
                }
            }
        }
    }

    private TransactionResponse successResponse(
            Transaction tx,
            TransactionStatus status,
            long gasUsed,
            long blockNum,
            Bytes output,
            String msg) {
        byte[] txHash = CryptoUtils.keccakHash(tx.toByteArray());
        return TransactionResponse.newBuilder()
                .setStatus(status)
                .setTransactionHash(ByteString.copyFrom(txHash))
                .setGasUsed(gasUsed)
                .setBlockNumber(blockNum)
                .setReturnData(ByteString.copyFrom(output.toArray()))
                .setErrorMessage(msg)
                .build();
    }

    private TransactionResponse errorResponse(Transaction tx, TransactionStatus status, String msg) {
        byte[] txHash = CryptoUtils.keccakHash(tx.toByteArray());
        return TransactionResponse.newBuilder()
                .setStatus(status)
                .setTransactionHash(ByteString.copyFrom(txHash))
                .setErrorMessage(msg)
                .build();
    }

    public static long calculateIntrinsicGas(Transaction tx) {
        // Standard transaction cost (G_tx)
        long gas = NATIVE_TRANSFER_GAS;

        // Contract creation cost (G_create)
        if (tx.getTo().isEmpty()) {
            gas += CONTRACT_CREATION_GAS;
        }

        // Calldata cost (G_txdata): 4 gas for zero bytes, 16 for non-zero
        byte[] data = tx.getData().toByteArray();
        for (byte b : data) {
            if (b == 0) {
                gas += 4;
            } else {
                gas += 16;
            }
        }
        return gas;
    }

    private static String decodeRevertReason(Bytes data, String defaultMsg) {
        if (data == null || data.size() < 4) {
            return defaultMsg;
        }

        // On REVERT, Solidity usually ABI-encodes the error exactly like a
        // function call payload: selector first, then encoded arguments.
        String selector = data.slice(0, 4).toHexString();
        if (ERROR_STRING_SELECTOR.equals(selector)) {
            return decodeErrorString(data).orElse(defaultMsg);
        }

        String customError = decodeCustomError(selector, data);
        return customError != null ? customError : "Reverted: " + data.toHexString();
    }

    private static Optional<String> decodeErrorString(Bytes data) {
        if (data.size() < 68) {
            return Optional.empty();
        }

        int length = readUint(data, 36).intValue();
        if (data.size() < 68 + length) {
            return Optional.empty();
        }
        return Optional.of("Revert: " + new String(data.slice(68, length).toArray(), StandardCharsets.UTF_8));
    }

    private static String decodeCustomError(String selector, Bytes data) {
        return switch (selector) {
            case "0xfb8f41b2" -> formatAllowanceError(data);
            case "0xe450d389" -> formatBalanceError(data);
            case "0x96c605d8" -> formatSingleAddressError("ERC20: Invalid Sender", data);
            case "0xec444d3e" -> formatSingleAddressError("ERC20: Invalid Receiver", data);
            default -> null;
        };
    }

    private static String formatAllowanceError(Bytes data) {
        if (data.size() < 100) {
            return null;
        }
        return String.format(
                "ERC20: Insufficient Allowance (Spender: %s, Has: %s, Needs: %s)",
                readAddress(data, 4),
                readUint(data, 36),
                readUint(data, 68));
    }

    private static String formatBalanceError(Bytes data) {
        if (data.size() < 100) {
            return null;
        }
        return String.format(
                "ERC20: Insufficient Balance (Sender: %s, Has: %s, Needs: %s)",
                readAddress(data, 4),
                readUint(data, 36),
                readUint(data, 68));
    }

    private static String formatSingleAddressError(String label, Bytes data) {
        if (data.size() < 36) {
            return null;
        }
        return label + " (" + readAddress(data, 4) + ")";
    }

    private static BigInteger readUint(Bytes data, int offset) {
        return data.slice(offset, 32).toBigInteger();
    }

    private static String readAddress(Bytes data, int offset) {
        return "0x" + CryptoUtils.bytesToHex(data.slice(offset + 12, 20).toArray());
    }

    private Address toBesuAddress(String address) {
        String normalized = address.startsWith("0x") ? address : "0x" + address;
        return Address.fromHexString(normalized);
    }

    /**
     * Captures the final execution result we need from Besu without coupling the
     * rest of the executor to Besu's internal frame model.
     */
    private static class ExecutionTracer implements OperationTracer {
        private long remainingGas;
        private boolean reverted;
        private Optional<Bytes> revertData = Optional.empty();
        private Optional<org.hyperledger.besu.evm.frame.ExceptionalHaltReason> haltReason = Optional.empty();
        private Bytes outputData = Bytes.EMPTY;

        @Override
        public void traceContextExit(MessageFrame frame) {
            remainingGas = frame.getRemainingGas();
            outputData = frame.getOutputData();

            MessageFrame.State state = frame.getState();
            if (state == MessageFrame.State.REVERT || state == MessageFrame.State.EXCEPTIONAL_HALT) {
                reverted = true;
            }

            if (frame.getRevertReason().isPresent()) {
                reverted = true;
                revertData = frame.getRevertReason();
            } else if (reverted && !outputData.isEmpty()) {
                // Some failures only surface their ABI-encoded error via the
                // frame output, so keep it as the best available explanation.
                revertData = Optional.of(outputData);
            }
        }

        @Override
        public void tracePostExecution(
                MessageFrame frame,
                org.hyperledger.besu.evm.operation.Operation.OperationResult operationResult) {
            org.hyperledger.besu.evm.frame.ExceptionalHaltReason halt = operationResult.getHaltReason();
            if (halt != null) {
                reverted = true;
                haltReason = Optional.of(halt);
            }
        }

        public long getRemainingGas() {
            return remainingGas;
        }

        boolean failed() {
            return reverted || haltReason.isPresent();
        }

        Bytes resolveOutput(Bytes executorOutput) {
            if (executorOutput != null && !executorOutput.isEmpty()) {
                return executorOutput;
            }
            return outputData;
        }

        String describeFailure(Bytes returnData) {
            if (haltReason.isPresent()) {
                return "Halted: " + haltReason.get().getDescription();
            }

            Bytes failureData = revertData.orElse(returnData);
            return decodeRevertReason(failureData, "Execution failed.");
        }
    }
}
