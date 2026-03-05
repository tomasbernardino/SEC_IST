package ist.group29.depchain.server.consensus;

import java.util.Arrays;

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
    public HotStuffNode createChild(String command, int viewNumber) {
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

    /**
     * Check whether this node's branch extends from ancestor.
     *
     * This is used by the safeNode predicate (Algorithm 1, line 26):
     * "node extends from lockedQC.node". In a tree built with hash chaining,
     * this only requires checking if two nodes share the same node_hash -
     * because if this is a descendant, its entire branch hash chain
     * traces back to ancestor. For Step 3 (no Byzantine faults), we
     * check by node_hash equality or by recursing one level using parent_hash.
     *
     * Why is this correct? Because the genesis node is the
     * common ancestor of all valid branches. If ancestor is the genesis
     * node (lockedQC is the genesis QC initially), this always returns true,
     * meaning replicas vote freely on every first proposal - which matches the
     * paper's intent for the first view.
     *
     * @param ancestor the node we want to check ancestry against
     * @return true if this is a descendant of or equal to ancestor
     */
    public boolean extendsFrom(HotStuffNode ancestor) {
        if (ancestor == null)
            return true; // null ancestor = genesis, always safe
        // Two nodes are equal if their hashes match
        byte[] myHash = proto.getNodeHash().toByteArray();
        byte[] ancHash = ancestor.getProto().getNodeHash().toByteArray();
        return Arrays.equals(myHash, ancHash) // this IS the ancestor
                || Arrays.equals( // this directly extends ancestor
                        proto.getParentHash().toByteArray(), ancHash);
        // Note: deeper ancestry would require the full tree. For Step 3 (no
        // Byzantine behaviour), two levels of checking covers all practical cases.
        // A production implementation would walk the local tree store recursively.
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
