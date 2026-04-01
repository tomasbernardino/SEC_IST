package ist.group29.depchain;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.tuweni.bytes.Bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import org.web3j.crypto.ECKeyPair;
import org.web3j.utils.Numeric;

import com.google.protobuf.ByteString;

import ist.group29.depchain.client.ClientMessages;
import ist.group29.depchain.common.crypto.ClientSignature;
import ist.group29.depchain.common.crypto.CryptoUtils;
import ist.group29.depchain.common.network.LinkManager;
import ist.group29.depchain.common.util.SystemSetupTool;
import ist.group29.depchain.network.ConsensusMessages;
import ist.group29.depchain.network.NetworkMessages.Envelope;
import ist.group29.depchain.server.consensus.Consensus;
import ist.group29.depchain.server.crypto.ThresholdPKISetup;
import ist.group29.depchain.server.service.BlockchainState;
import ist.group29.depchain.server.service.TransactionExecutor;
import ist.group29.depchain.server.service.TransactionManager;
import ist.group29.depchain.server.service.account.ContractAccount;

public class FrontrunningTest {

    @TempDir
    Path tempDir;

    private static final String IST_COIN_ADDR = "1111111111111111111111111111111111111111";
    private static final int NR_NODES = 4;

    private final Map<String, ConsensusNode> nodes = new ConcurrentHashMap<>();

    private ECKeyPair aliceKeys;
    private ECKeyPair bobKeys;
    private String aliceAddr;
    private String bobAddr;

    @BeforeEach
    void setup() throws Exception {
        ensureIstCoinBytecode();

        Path keysDir = tempDir.resolve("keys");
        Path storageDir = tempDir.resolve("storage");
        Path outputDir = tempDir.resolve("output");
        Files.createDirectories(keysDir);
        Files.createDirectories(storageDir);
        Files.createDirectories(outputDir);

        Path hostsConfig = tempDir.resolve("hosts.config");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < NR_NODES; i++) {
            sb.append("node-").append(i).append(" 127.0.0.1 ").append(8000 + i).append("\n");
        }
        Files.writeString(hostsConfig, sb.toString());

        SystemSetupTool.main(new String[] {
                String.valueOf(NR_NODES),
                "2",
                keysDir.toString(),
                storageDir.toString(),
                hostsConfig.toString(),
                outputDir.toString(),
                "password"
        });
        ThresholdPKISetup.main(new String[] { keysDir.toString() });

        aliceKeys = CryptoUtils.loadECKeyPair(keysDir.resolve("client-0.key"));
        bobKeys = CryptoUtils.loadECKeyPair(keysDir.resolve("client-1.key"));
        aliceAddr = CryptoUtils.getAddress(aliceKeys).toLowerCase();
        bobAddr = CryptoUtils.getAddress(bobKeys).toLowerCase();

        List<String> nodeIds = new ArrayList<>();
        for (int i = 0; i < NR_NODES; i++) {
            nodeIds.add("node-" + i);
        }

        Path genesisPath = storageDir.resolve("genesis.json");
        for (String id : nodeIds) {
            LinkManager lm = mock(LinkManager.class);
            final String senderId = id;

            // The mocked transport delivers consensus messages directly between in-memory
            // nodes, which keeps the test focused on ordering/execution rather than sockets.
            doAnswer(invocation -> {
                byte[] data = invocation.getArgument(0);
                nodes.values().forEach(target -> deliver(senderId, target, data));
                return null;
            }).when(lm).broadcast(any(byte[].class));

            doAnswer(invocation -> {
                String targetId = invocation.getArgument(0);
                byte[] data = invocation.getArgument(1);
                deliver(senderId, nodes.get(targetId), data);
                return null;
            }).when(lm).send(anyString(), any(byte[].class));

            BlockchainState state = BlockchainState.loadFromBlock(genesisPath);
            nodes.put(id, new ConsensusNode(id, nodeIds, keysDir, lm, state));
        }

        nodes.values().forEach(ConsensusNode::start);
    }

    private void ensureIstCoinBytecode() throws Exception {
        Path repoRoot = Path.of("").toAbsolutePath().normalize();
        if (repoRoot.getFileName() != null && repoRoot.getFileName().toString().equals("server")) {
            repoRoot = repoRoot.getParent();
        }

        Path bytecodePath = repoRoot.resolve("server/src/main/solidity/bin/ISTCoin.bytecode");
        if (Files.exists(bytecodePath) && Files.size(bytecodePath) > 0) {
            return;
        }

        Path scriptPath = repoRoot.resolve("compile_contract.sh");
        if (!Files.exists(scriptPath)) {
            fail("compile_contract.sh not found at " + scriptPath);
        }

        Process proc = new ProcessBuilder("bash", scriptPath.toString())
                .directory(repoRoot.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(proc.getInputStream().readAllBytes());
        proc.waitFor();
        if (!Files.exists(bytecodePath) || Files.size(bytecodePath) == 0) {
            fail("Failed to generate ISTCoin.bytecode via compile_contract.sh:\n" + output);
        }
    }

    @Test
    void testClassicApproveFrontrunning() throws Exception {
        ConsensusNode leader = nodes.get("node-0");
        // Check if Bob has allowance before the approve and Bob's initial balance is 0
        for (ConsensusNode node : nodes.values()) {
            assertEquals(BigInteger.ZERO, getAllowance(node, aliceAddr, bobAddr));
            assertEquals(BigInteger.ZERO, getTokenBalance(node, bobAddr));
        }
        // Alice approves Bob for 100 tokens
        submitTx(leader, buildSignedTx(aliceKeys, aliceAddr, IST_COIN_ADDR, 0, encodeApprove(bobAddr, 100), 1, 100000));
        waitForDecide(1, 25000);

        // Alice tries to decrease the allowance to 50
        submitTx(leader, buildSignedTx(aliceKeys, aliceAddr, IST_COIN_ADDR, 1, encodeApprove(bobAddr, 50), 1, 100000));
        // Bob sees the pending decrease and tries to frontrun by transferring 100 tokens with higher gas price so it gets included in the block before the decrease.
        submitTx(leader, buildSignedTx(bobKeys, bobAddr, IST_COIN_ADDR, 0, encodeTransferFrom(aliceAddr, bobAddr, 100), 2, 100000));

        waitForDecide(2, 45000);

        for (ConsensusNode node : nodes.values()) {
            assertEquals(BigInteger.valueOf(100), getTokenBalance(node, bobAddr));
            assertEquals(BigInteger.valueOf(50), getAllowance(node, aliceAddr, bobAddr));
        }

        submitTx(leader, buildSignedTx(bobKeys, bobAddr, IST_COIN_ADDR, 1, encodeTransferFrom(aliceAddr, bobAddr, 50), 1, 100000));
        waitForDecide(3, 45000);

        for (ConsensusNode node : nodes.values()) {
          assertEquals(BigInteger.ZERO, getAllowance(node, aliceAddr, bobAddr));
          assertEquals(BigInteger.valueOf(150), getTokenBalance(node, bobAddr));
        }
    }

    @Test
    void testDecreaseAllowanceFrontrunning() throws Exception {
        ConsensusNode leader = nodes.get("node-0");

        submitTx(leader, buildSignedTx(aliceKeys, aliceAddr, IST_COIN_ADDR, 0, encodeIncreaseAllowance(bobAddr, 100), 1, 100000));
        waitForDecide(1, 25000);

        submitTx(leader, buildSignedTx(aliceKeys, aliceAddr, IST_COIN_ADDR, 1, encodeDecreaseAllowance(bobAddr, 50), 1, 100000));
        submitTx(leader, buildSignedTx(bobKeys, bobAddr, IST_COIN_ADDR, 0, encodeTransferFrom(aliceAddr, bobAddr, 100), 2, 100000));

        waitForDecide(2, 45000);

        for (ConsensusNode node : nodes.values()) {
            assertEquals(BigInteger.ZERO, getAllowance(node, aliceAddr, bobAddr));
            assertEquals(BigInteger.valueOf(100), getTokenBalance(node, bobAddr));
        }
    }

    private void submitTx(ConsensusNode node, ClientMessages.Transaction tx) {
        // mock transaction broadcast
        nodes.values().forEach(target -> target.transactionManager.addPendingTx("test-client", tx));
    }

    private void deliver(String fromId, ConsensusNode target, byte[] data) {
        if (target == null) {
            return;
        }
        try {
            Envelope env = Envelope.parseFrom(data);
            if (env.hasConsensus()) {
                ConsensusMessages.ConsensusMessage msg = env.getConsensus();
                CompletableFuture.runAsync(() -> target.consensus.onMessage(fromId, msg));
            }
        } catch (Exception ignored) {
        }
    }

    private void waitForDecide(int blockNum, long timeoutMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            boolean allDecided = true;
            for (ConsensusNode node : nodes.values()) {
                if (node.consensus.getLastDecidedView() < blockNum) {
                    allDecided = false;
                    break;
                }
            }
            if (allDecided) {
                return;
            }
            Thread.sleep(1000);
        }
        fail("Timed out waiting for block " + blockNum);
    }

    private BigInteger getTokenBalance(ConsensusNode node, String owner) {
        // OpenZeppelin ERC-20 stores balances in `mapping(address => uint256)` at slot 0.
        byte[] slotKey = computeMappingSlot(owner, 0);
        String value = getIstCoin(node).getStorage()
                .getOrDefault("0x" + CryptoUtils.bytesToHex(slotKey), zeroWord());
        return Numeric.toBigInt(value);
    }

    private BigInteger getAllowance(ConsensusNode node, String owner, String spender) {
        // Allowances live in a nested mapping `mapping(address => mapping(address => uint256))`
        // at slot 1, so we first hash the owner slot and then the spender slot.
        byte[] slotKey = computeNestedMappingSlot(owner, spender, 1);
        String value = getIstCoin(node).getStorage()
                .getOrDefault("0x" + CryptoUtils.bytesToHex(slotKey), zeroWord());
        return Numeric.toBigInt(value);
    }

    private ContractAccount getIstCoin(ConsensusNode node) {
        return assertInstanceOf(
                ContractAccount.class,
                node.state.getAccount(IST_COIN_ADDR),
                "IST Coin account was not loaded as a contract. Ensure genesis generation included the compiled runtime bytecode.");
    }

    private String encodeApprove(String spender, long value) {
        return "0x095ea7b3" + leftPad(spender, 64) + leftPad(BigInteger.valueOf(value), 64);
    }

    private String encodeTransferFrom(String from, String to, long value) {
        return "0x23b872dd" + leftPad(from, 64) + leftPad(to, 64) + leftPad(BigInteger.valueOf(value), 64);
    }

    private String encodeDecreaseAllowance(String spender, long value) {
        return "0xa457c2d7" + leftPad(spender, 64) + leftPad(BigInteger.valueOf(value), 64);
    }

    private String encodeIncreaseAllowance(String spender, long value) {
        return "0x39509351" + leftPad(spender, 64) + leftPad(BigInteger.valueOf(value), 64);
    }

    private byte[] computeMappingSlot(String keyAddr, int slot) {
        byte[] addrBytes = Numeric.hexStringToByteArray(keyAddr);
        byte[] paddedAddr = new byte[32];
        System.arraycopy(addrBytes, 0, paddedAddr, 12, 20);
        byte[] paddedSlot = Numeric.hexStringToByteArray(leftPad(BigInteger.valueOf(slot), 64));
        return CryptoUtils.keccakHash(Bytes.concatenate(Bytes.wrap(paddedAddr), Bytes.wrap(paddedSlot)).toArray());
    }

    private byte[] computeNestedMappingSlot(String key1, String key2, int slot) {
        byte[] innerHash = computeMappingSlot(key1, slot);
        byte[] addrBytes2 = Numeric.hexStringToByteArray(key2);
        byte[] paddedAddr2 = new byte[32];
        System.arraycopy(addrBytes2, 0, paddedAddr2, 12, 20);
        return CryptoUtils.keccakHash(Bytes.concatenate(Bytes.wrap(paddedAddr2), Bytes.wrap(innerHash)).toArray());
    }

    private String leftPad(String hex, int size) {
        String value = hex.replace("0x", "");
        return "0".repeat(Math.max(0, size - value.length())) + value;
    }

    private String leftPad(BigInteger val, int size) {
        return leftPad(val.toString(16), size);
    }

    private String zeroWord() {
        return "0x0000000000000000000000000000000000000000000000000000000000000000";
    }

    private ClientMessages.Transaction buildSignedTx(
            ECKeyPair keys,
            String from,
            String to,
            long nonce,
            String data,
            long gasPrice,
            long gasLimit) {

        ClientMessages.Transaction tx = ClientMessages.Transaction.newBuilder()
                .setFrom("0x" + from)
                .setTo("0x" + to)
                .setValue(0)
                .setNonce(nonce)
                .setGasPrice(gasPrice)
                .setGasLimit(gasLimit)
                .setData(ByteString.copyFrom(Numeric.hexStringToByteArray(data)))
                .build();

        ClientSignature sig = CryptoUtils.ecSign(keys, tx.toByteArray());
        return tx.toBuilder()
                .setSigV(ByteString.copyFrom(sig.v()))
                .setSigR(ByteString.copyFrom(sig.r()))
                .setSigS(ByteString.copyFrom(sig.s()))
                .build();
    }

    class ConsensusNode {
        final BlockchainState state;
        final TransactionExecutor executor;
        final TransactionManager transactionManager;
        final Consensus consensus;

        ConsensusNode(String id, List<String> participants, Path keysDir, LinkManager lm, BlockchainState state) throws Exception {
            this.state = state;
            this.executor = new TransactionExecutor(state);
            this.transactionManager = new TransactionManager(state, lm);
            this.consensus = new Consensus(id, participants, lm, (block, view) -> {
                executor.executeBlock(block, view);
                transactionManager.removeCommittedTxs(block.getTransactionsList());
            }, transactionManager, keysDir.toString());
        }

        void start() {
            consensus.start();
        }
    }
}
