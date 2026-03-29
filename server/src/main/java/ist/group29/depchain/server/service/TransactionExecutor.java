package ist.group29.depchain.server.service;

import java.math.BigInteger;
import java.util.Map;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * TransactionExecutor ensures that every transaction is processed deterministically and
 * correctly pays for its own computation/resource usage in DepCoin.
 */
public class TransactionExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(TransactionExecutor.class);

    // Standard Ethereum cost for a simple transfer, in gas units
    private static final long NATIVE_TRANSFER_GAS = 21000;
    private static final long CONTRACT_CREATION_GAS = 32000;

    private final BlockchainState state;

    public TransactionExecutor(BlockchainState state) {
        this.state = state;
    }

    /**
     * Executes all transactions in a block sequentially.
     */
    public java.util.List<TransactionResponse> executeBlock(Block block, long blockNumber) {
        LOG.info("[Executor] Executing block #{} with {} transactions", blockNumber, block.getTransactionsList().size());
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
        String fromAddr = tx.getFrom().replace("0x", "").toLowerCase();

        // Pre-execution validation
        BlockchainAccount senderAcc = state.getAccount(fromAddr);
        if (!(senderAcc instanceof EOA)) {
            return errorResponse(tx, TransactionStatus.INVALID_SIGNATURE, "Sender must be an EOA.");
        }
        EOA sender = (EOA) senderAcc;

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
        } else {
            if (tx.getTo().isEmpty()) {
                return executeContractCreation(tx, sender, blockNumber);
            } else {
                return executeContractCall(tx, sender, blockNumber);
            }
        }
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

        String toAddr = tx.getTo().replace("0x", "").toLowerCase();
        BlockchainAccount receiverAcc = state.getOrCreateAccount(toAddr);
        sender.debit(BigInteger.valueOf(tx.getValue()));
        receiverAcc.credit(BigInteger.valueOf(tx.getValue()));

        sender.incrementNonce();
        return successResponse(tx, TransactionStatus.SUCCESS, gasUsed, blockNumber, Bytes.EMPTY, "Transfer complete.");
    }

    private TransactionResponse executeContractCall(Transaction tx, EOA sender, long blockNumber) {
        return executeInEVM(tx, sender, Address.fromHexString(tx.getTo()), blockNumber, false);
    }

    private TransactionResponse executeContractCreation(Transaction tx, EOA sender, long blockNumber) {
        return executeInEVM(tx, sender, null, blockNumber, true);
    }

    // Executes a transaction in the EVM and returns the response. Handles both contract calls and creations.
    private TransactionResponse executeInEVM(Transaction tx, EOA sender, Address receiver, long blockNumber,
            boolean isCreation) {
        SimpleWorld besuWorld = new SimpleWorld();
        bridgeStateToBesu(state, besuWorld);

        GasTracer gasTracer = new GasTracer();
        EVMExecutor evmExecutor = EVMExecutor.evm(EvmSpecVersion.CANCUN);
        evmExecutor.worldUpdater(besuWorld.updater());
        evmExecutor.tracer(gasTracer);
        evmExecutor.sender(Address.fromHexString(sender.getAddress()));
        if (receiver != null) {
            evmExecutor.receiver(receiver);
        }
        evmExecutor.gas(tx.getGasLimit());
        evmExecutor.callData(Bytes.wrap(tx.getData().toByteArray()));
        evmExecutor.ethValue(Wei.of(tx.getValue()));

        Bytes output;
        long gasUsed;
        try {
            output = evmExecutor.execute();

            // Use the gas used from the EVM tracer.
            long intrinsicGas = calculateIntrinsicGas(tx);
            long opcodeGas = tx.getGasLimit() - gasTracer.getRemainingGas();
            gasUsed = intrinsicGas + opcodeGas;

            if (gasUsed > tx.getGasLimit()) {
                gasUsed = tx.getGasLimit();
            }

            chargeGas(sender, gasUsed, tx.getGasPrice());
            commitBesuChanges(besuWorld, state);
            sender.incrementNonce();

            String msg = isCreation ? "Contract Deployed Successfully." : "Execution Succeeded.";
            return successResponse(tx, TransactionStatus.SUCCESS, gasUsed, blockNumber, output, msg);
        } catch (Exception e) {
            LOG.error("[Executor] EVM Execution failed: " + e.getMessage());
            gasUsed = tx.getGasLimit();
            chargeGas(sender, gasUsed, tx.getGasPrice());
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
            Address addr = Address.fromHexString(acc.getAddress());
            long nonce = (acc instanceof EOA eoa) ? eoa.getNonce() : 0;
            target.createAccount(addr, nonce, Wei.of(acc.getBalance()));

            MutableAccount mutable = (MutableAccount) target.get(addr);
            if (acc.isContract()) {
                ContractAccount ca = (ContractAccount) acc;
                mutable.setCode(Bytes.fromHexString(ca.getCode()));
                for (Map.Entry<String, String> slot : ca.getStorage().entrySet()) {
                    mutable.setStorageValue(
                            UInt256.fromHexString(slot.getKey()),
                            UInt256.fromHexString(slot.getValue()));
                }
            }
        }
    }

    private void commitBesuChanges(SimpleWorld besuWorld, BlockchainState destination) {
        for (org.hyperledger.besu.evm.account.Account besuAcc : besuWorld.getTouchedAccounts()) {
            String addr = besuAcc.getAddress().toHexString().replace("0x", "").toLowerCase();
            BlockchainAccount ourAcc = destination.getOrCreateAccount(addr);

            // Sync balance and nonce
            ourAcc.setBalance(besuAcc.getBalance().toBigInteger());
            if (ourAcc instanceof EOA eoa) {
                eoa.setNonce(besuAcc.getNonce());
            }

            // Sync code and storage for contracts
            boolean hasCode = besuAcc.getCode() != null && !besuAcc.getCode().isEmpty();
            if (hasCode || (ourAcc instanceof ContractAccount)) {
                ContractAccount ca;
                if (!(ourAcc instanceof ContractAccount)) {
                    // Upgrade to contract account (e.g. on deployment)
                    ca = new ContractAccount(addr);
                    ca.setBalance(ourAcc.getBalance());
                    destination.addAccount(ca);
                } else {
                    ca = (ContractAccount) ourAcc;
                }
                
                if (hasCode) {
                    ca.setCode(besuAcc.getCode().toHexString());
                }

                // Sync storage slots
                if (besuAcc instanceof org.hyperledger.besu.evm.account.MutableAccount mutable) {
                    Map<UInt256, UInt256> updatedStorage = mutable.getUpdatedStorage();
                    for (Map.Entry<UInt256, UInt256> slot : updatedStorage.entrySet()) {
                        ca.getStorage().put(slot.getKey().toHexString(), slot.getValue().toHexString());
                    }
                }
            }
        }
    }

    private TransactionResponse successResponse(Transaction tx, TransactionStatus status, long gasUsed, long blockNum,
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

    private static class GasTracer implements OperationTracer {
        private long remainingGas = 0;

        @Override
        public void traceContextExit(MessageFrame frame) {
            this.remainingGas = frame.getRemainingGas();
        }

        public long getRemainingGas() {
            return remainingGas;
        }
    }
}
