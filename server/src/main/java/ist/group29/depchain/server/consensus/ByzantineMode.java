package ist.group29.depchain.server.consensus;

/**
 * Byzantine behavior modes for manual test.
 */
public enum ByzantineMode {

    /** Normal behavior (default) */
    CORRECT,

    /** Node stops responding to all consensus messages */
    SILENT,

    /** Node crashes after a delay */
    CRASH,

    /** Leader sends proposals with an invalid block */
    CORRUPT_PROPOSAL,

    /** Leader corrupts QC signatures when aggregating votes */
    CORRUPT_QC,

    /** Replica sends votes with corrupted signature shares */
    CORRUPT_VOTE,

    /** Leader delays all message processing */
    SLOW_NODE,

    /** Replica only votes in PREPARE, drops PRE_COMMIT and COMMIT votes */
    SELECTIVE_VOTE,

    /** Leader replays a previously committed block as a new proposal */
    REPLAY_LEADER
}
