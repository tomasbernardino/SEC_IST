package ist.group29.depchain.server.consensus;

import com.google.protobuf.ByteString;

import ist.group29.depchain.common.crypto.CryptoUtils;
import ist.group29.depchain.network.ConsensusMessages;

/**
 * Thin Java wrapper around the Protobuf of HotStuffNode.
 *
 * In Basic HotStuff, each node of the command
 * tree contains a proposed command, a parent link (expressed as a hash digest),
 * and metadata. The node_hash field binds all three fields together
 * with a SHA-256 digest:
 * node_hash = SHA-256(parent_hash || command_bytes || view_number_bytes)
 *
 * We use hash-chaining since the paper says:
 * "The method creates a new leaf node as a child and embeds a digest of the
 * parent in the child node." This makes it computationally infeasible to forge
 * a branch, since changing any ancestor would invalidate every descendant hash.
 */
public class HotStuffNode {

    /** Singleton genesis node representing the initial empty tree root (view 0). */
    private static final HotStuffNode GENESIS;

    static {
        byte[] zeroHash = new byte[32]; // 32 zero bytes for genesis parent hash
        GENESIS = new HotStuffNode(
                ConsensusMessages.HotStuffNode.newBuilder()
                        .setParentHash(ByteString.copyFrom(zeroHash))
                        .setCommand("")
                        .setViewNumber(0)
                        .setNodeHash(ByteString.copyFrom(CryptoUtils.computeHash(zeroHash, "", 0)))
                        .build());
    }

    private final ConsensusMessages.HotStuffNode proto;

    /** Wrap an existing Protobuf HotStuffNode. */
    public HotStuffNode(ConsensusMessages.HotStuffNode proto) {
        this.proto = proto;
    }

    /** Return the genesis node - the implicit root of the command tree. */
    public static HotStuffNode genesis() {
        return GENESIS;
    }

    /**
     * Create a new leaf node as a child of this using the
     * createLeaf(parent, cmd) method from Algorithm 1 of the paper.
     *
     * @param command    the client command string to embed in this node
     * @param viewNumber the view number of the proposal
     * @return a new child HotStuffNode
     */
    public HotStuffNode createLeaf(String command, int viewNumber) {
        byte[] parentHash = proto.getNodeHash().toByteArray();
        byte[] nodeHash = CryptoUtils.computeHash(parentHash, command, viewNumber);
        ConsensusMessages.HotStuffNode child = ConsensusMessages.HotStuffNode.newBuilder()
                .setParentHash(ByteString.copyFrom(parentHash))
                .setCommand(command)
                .setViewNumber(viewNumber)
                .setNodeHash(ByteString.copyFrom(nodeHash))
                .build();
        return new HotStuffNode(child);
    }

    /** Serialise to Protobuf for inclusion in network messages. */
    public ConsensusMessages.HotStuffNode getProto() {
        return proto;
    }

    /** The SHA-256 hash that uniquely identifies this node. */
    public byte[] getNodeHash() {
        return proto.getNodeHash().toByteArray();
    }

    /** The proposed command string. */
    public String getCommand() {
        return proto.getCommand();
    }

    /** The view number in which this node was proposed. */
    public int getViewNumber() {
        return proto.getViewNumber();
    }

    @Override
    public String toString() {
        return "HotStuffNode{view=" + proto.getViewNumber()
                + ", cmd='" + proto.getCommand() + "'"
                + ", hash=" + CryptoUtils.bytesToHex(proto.getNodeHash().toByteArray(), 4) + "}";
    }

}
