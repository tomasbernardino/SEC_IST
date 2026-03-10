package ist.group29.depchain.server.consensus;

import com.google.protobuf.ByteString;

import ist.group29.depchain.common.crypto.CryptoUtils;
import ist.group29.depchain.network.ConsensusMessages;

/**
 * Java wrapper around the Protobuf of HotStuffNode.
 */
public class HotStuffNode {

    /** Genesis node representing the initial empty tree root (view 0) */
    private static final HotStuffNode GENESIS;

    static {
        byte[] zeroHash = new byte[32];
        GENESIS = new HotStuffNode(
                ConsensusMessages.HotStuffNode.newBuilder()
                        .setParentHash(ByteString.copyFrom(zeroHash))
                        .setCommand("")
                        .setViewNumber(0)
                        .setNodeHash(ByteString.copyFrom(CryptoUtils.computeHash(zeroHash, "", 0)))
                        .build());
    }

    private final ConsensusMessages.HotStuffNode proto;

    public HotStuffNode(ConsensusMessages.HotStuffNode proto) {
        this.proto = proto;
    }

    public static HotStuffNode genesis() {
        return GENESIS;
    }

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

    public ConsensusMessages.HotStuffNode getProto() {
        return proto;
    }

    /** The SHA-256 hash that uniquely identifies this node */
    public byte[] getNodeHash() {
        return proto.getNodeHash().toByteArray();
    }

    /** The proposed command string */
    public String getCommand() {
        return proto.getCommand();
    }

    /** The view number in which this node was proposed */
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
