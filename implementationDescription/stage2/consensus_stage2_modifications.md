# Stage 2: Consensus & Routing Modifications

This document covers the changes made to the consensus and networking layers to support the block-based transaction architecture. Three major subsystems were introduced: an **Envelope wire protocol** for deterministic message multiplexing, a **message router** for client request routing, and a **proposal retry timer** for block-based consensus.

## 1. Envelope Wire Protocol

### 1.1 Problem

Stage 1 had a single application message type (`ConsensusMessage`) sent over the wire. Stage 2 introduces `Transaction` as a first-class message type alongside `ConsensusMessage`, and `TransactionResponse` for server-to-client replies. Without a multiplexing mechanism, the receiver must guess the message type from the payload — a fragile heuristic that breaks if proto field numbers or wire types change.

### 1.2 Design

All application messages are wrapped in an `Envelope` proto with an explicit `oneof`:

```proto
message Envelope {
    oneof payload {
        ConsensusMessage     consensus            = 1;
        Transaction          transaction           = 2;
        TransactionResponse  transaction_response  = 3;
    }
}
```

This is the standard protobuf pattern for multiplexing different message types over a single channel. The `oneof` ensures each message is unambiguously typed on the wire — no heuristics, no try-parse, no false positives.

**Overhead:** ~2 bytes per message (protobuf tag for the oneof field + length varint).

### 1.3 Why Not Try-Parse

An earlier version of the router used two-phase protobuf parsing: try `ConsensusMessage.parseFrom()`, check if `oneof type` is set, then try `Transaction.parseFrom()`, check if `from` is non-empty. This worked because `ConsensusMessage` and `Transaction` happen to have incompatible wire types for the same field numbers. But it was fragile:

- If someone renumbers a field in either proto, the wire types could align and the parser would route messages to the wrong handler silently
- The validation checks (`TYPE_NOT_SET`, `!from.isEmpty()`) are semantic, not structural
- Adding a new message type means rewriting the parser

The `Envelope` approach makes the type explicit and deterministic.

### 1.4 EnvelopeFactory

A static utility class wraps outgoing messages before handing bytes to `LinkManager`:

```java
public final class EnvelopeFactory {
    public static byte[] wrap(ConsensusMessage msg) { ... }
    public static byte[] wrap(Transaction tx) { ... }
    public static byte[] wrap(TransactionResponse resp) { ... }
}
```

Senders call `EnvelopeFactory.wrap(msg)` and pass the result to `linkManager.broadcast()` or `linkManager.send()`. The factory is in the `common` module so both server and client can use it.

### 1.5 Files Changed

| File | Change |
| :--- | :--- |
| `common/.../proto/messages.proto` | Added `Envelope` message with `oneof { ConsensusMessage, Transaction, TransactionResponse }` + imports of `consensus.proto` and `client.proto` |
| `common/.../common/network/EnvelopeFactory.java` | **New** — static `wrap()` methods for all 3 message types |

---

## 2. Message Router — Client Request Routing

### 2.1 Problem

In Stage 1, all incoming network payloads were bound to `consensus::onMessage()` via a single `MessageListener`. Stage 2 introduces `Transaction` as a first-class message type. Without routing, client transactions arriving at a node would reach the consensus handler, fail to parse, and be dropped.

### 2.2 Design

`MessageRouter` implements `MessageListener` and sits between `LinkManager` and the two consumers (`Consensus`, `Service`). It parses the incoming `Envelope` and switches on the payload case:

```java
@Override
public void onMessage(String senderId, byte[] raw) {
    Envelope env = Envelope.parseFrom(raw);  // throws on malformed

    switch (env.getPayloadCase()) {
        case CONSENSUS    -> consensus.onMessage(senderId, env.getConsensus());
        case TRANSACTION  -> service.addPendingTx(senderId, env.getTransaction());
        case TRANSACTION_RESPONSE -> LOG.fine("[Router] TransactionResponse (no handler yet)");
        case PAYLOAD_NOT_SET -> LOG.warning("[Router] Empty envelope from " + senderId);
    }
}
```

**Key properties:**
- **Deterministic** — `getPayloadCase()` is an exhaustive enum; the `switch` with no `default` warns if a new case is added and not handled
- **Single parse** — the `Envelope` parse extracts the inner message natively via `oneof`; no double serialization
- **Type-safe** — `env.getConsensus()` returns a `ConsensusMessage` directly, not raw `byte[]`

### 2.3 Consensus onMessage Refactor

`Consensus.onMessage()` was refactored to accept a pre-parsed `ConsensusMessage` instead of raw `byte[]`:

```java
// Before:
public void onMessage(String senderId, byte[] payload) {
    ConsensusMessage msg = ConsensusMessage.parseFrom(payload);  // redundant
    switch (msg.getTypeCase()) { ... }
}

// After:
public void onMessage(String senderId, ConsensusMessage msg) {
    switch (msg.getTypeCase()) { ... }
}
```

The redundant `parseFrom()` inside `onMessage()` is eliminated — the router already parsed the `ConsensusMessage` from the envelope. The `try/catch (InvalidProtocolBufferException)` wrapper is also removed since the object is already parsed.

A backward-compatible `byte[]` overload was **not** kept — the `byte[]` path is obsolete once the envelope is in place.

### 2.4 Consensus Decoupling from MessageListener

`Consensus` previously implemented `MessageListener` to plug directly into `LinkManager`. With `MessageRouter` being the sole `MessageListener`, this coupling was removed:

- Removed `implements MessageListener` from the class declaration
- Removed `@Override` from `onMessage()`
- Method is called directly by the router, not via the interface

### 2.5 All Send/Broadcast Sites Wrapped

Every `linkManager.broadcast()` and `linkManager.send()` call in `Consensus` now wraps the message with `EnvelopeFactory.wrap()` before passing bytes to the network layer:

```java
// Example: PREPARE broadcast
ConsensusMessage prepareMsg = ConsensusMessage.newBuilder()
        .setViewNumber(curView)
        .setPrepare(prepare)
        .build();
linkManager.broadcast(EnvelopeFactory.wrap(prepareMsg));
```

All 4 broadcast sites and 4 unicast send sites were updated:
- `triggerProposal()` — PREPARE broadcast
- `onVote()` quorum — PRE_COMMIT, COMMIT, DECIDE broadcasts
- `sendNewView()` — NEW-VIEW unicast to next leader
- `sendVote()` — VOTE unicast to current leader
- `sendSyncRequest()` — SYNC_REQUEST unicast
- `onSyncRequest()` — SYNC_RESPONSE unicast

### 2.6 Service Layer Changes

Two methods were added to `Service`:

- **`addPendingTx(String senderId, Transaction tx)`** — receives transactions from the router, stores in `List<Transaction> mempool`. Thread-safe via `synchronized(mempool)`.
- **`drainMempool(int maxTx)`** — pulls up to `maxTx` transactions from the front of the mempool for block building.

The missing `Transaction` import was also fixed (the class was referenced but unimported in `Service.java`).

### 2.7 Files Changed

| File | Change |
| :--- | :--- |
| `server/.../server/MessageRouter.java` | **New** — envelope-based dispatcher (~49 lines) |
| `server/.../server/App.java` | `setMessageListener(consensus::onMessage)` → `setMessageListener(new MessageRouter(consensus, service))` |
| `server/.../server/service/Service.java` | Added `mempool`, `addPendingTx()`, `drainMempool()`, fixed `Transaction` import |
| `server/.../server/consensus/Consensus.java` | Removed `implements MessageListener`, new `onMessage(String, ConsensusMessage)` signature, all 8 send/broadcast sites wrapped with `EnvelopeFactory.wrap()`, added `EnvelopeFactory` import |

---

## 3. Proposal Retry Timer — Block Proposal Mechanism

### 3.1 Problem

In Stage 1, `triggerProposal()` fired once — inside `onNewView()` after the NEW-VIEW quorum arrived. If the mempool was empty at that instant, `buildBlock()` returned an empty block, `triggerProposal()` returned early, and the leader stalled until the next view timeout (4+ seconds with exponential backoff). Transactions arriving later had no path to trigger a proposal.

### 3.2 Design Decision: On-Demand Self-Scheduling Retry

Three approaches were considered:

1. **Periodic timer** (e.g., every 1 second) — polls `triggerProposal()` continuously. Simple but wasteful — non-leaders do unnecessary work, and the timer exists in parallel with the view timeout.

2. **Callback from Service** — when a transaction arrives at `addPendingTx()`, notify Consensus to retry `triggerProposal()`. Event-driven and responsive, but introduces a reverse dependency (Service → Consensus) via a new `ProposalListener` interface.

3. **On-demand self-scheduling retry** — `triggerProposal()` schedules its own retry when the mempool is empty. No timer exists until quorum is reached and a proposal attempt is made. The retry self-schedules every 500ms until a block is built or the view changes.

Option 3 was chosen. It is inspired by **LibraBFT/DiemBFT**, where proposals are event-driven (NEW-VIEW quorum triggers proposal immediately) and the timer exists only for liveness (view change). The on-demand retry handles the edge case where the mempool is empty when the leader proposes, but transactions arrive shortly after.

#### Why option 3 over the others

| Aspect | Periodic timer | Callback | On-demand self-scheduling |
| :--- | :--- | :--- | :--- |
| Latency | Up to 1s | Immediate | 500ms intervals |
| Resource usage | Polls from all nodes every second | Fires only when needed | Only fires after quorum + empty block |
| Coupling | None | Service → Consensus | None (self-contained in Consensus) |
| Lifecycle | Separate timer | New interface | Self-managed by `triggerProposal()` |
| Pre-quorum waste | Yes (fires for non-leaders too) | No | No (only scheduled after quorum) |

The on-demand approach has zero cross-layer coupling, no new interfaces, no pre-quorum waste, and the lifecycle is self-managed — the retry only exists when needed.

### 3.3 Implementation

A `blockProposalTimer` field holds the current scheduled retry. A `scheduleRetry()` method cancels any existing timer and schedules a one-shot call to `triggerProposal()` after `PROPOSAL_RETRY_MS = 500ms`:

```java
private static final long PROPOSAL_RETRY_MS = 500;
private ScheduledFuture<?> blockProposalTimer = null;

private void scheduleRetry() {
    cancelBlockProposalTimer();
    blockProposalTimer = pacemaker.schedule(this::triggerProposal, PROPOSAL_RETRY_MS, TimeUnit.MILLISECONDS);
}

private void cancelBlockProposalTimer() {
    if (blockProposalTimer != null) {
        blockProposalTimer.cancel(false);
    }
}
```

In `triggerProposal()`, the retry is scheduled on-demand:

```java
Block block = decideListener.buildBlock();
if (block == null || block.getTransactionsCount() == 0) {
    scheduleRetry();   // Self-schedule for retry in 500ms
    return;
}

// ... propose successfully ...
cancelBlockProposalTimer(); // Proposal is out, no need for retry
```

**Lifecycle:**
- **Created**: inside `triggerProposal()` when `buildBlock()` returns empty — only after quorum is confirmed
- **Canceled**: inside `triggerProposal()` when proposal succeeds, or inside `cancelTimer()` (called by `advanceView()` on view change)
- **`resetTimer()`**: only schedules the view timeout timer — the retry is not pre-scheduled

### 3.4 triggerProposal Quorum Guard

`triggerProposal()` includes its own quorum check so it can be called from the retry timer independently of `onNewView()`:

```java
public synchronized void triggerProposal() {
    if (!isLeader(curView)) return;
    if (currentProposal != null) return;
    Map<String, NewViewMessage> newViewMessages = newViewAccumulator.get(curView);
    if (newViewMessages == null || newViewMessages.size() < quorum || highQC == null)
        return;

    Block block = decideListener.buildBlock();
    if (block == null || block.getTransactionsCount() == 0) {
        scheduleRetry();
        return;
    }

    // ... propose ...
    cancelBlockProposalTimer();
}
```

This ensures the leader always waits for n-f NEW-VIEW messages before proposing, regardless of whether `triggerProposal()` is called from `onNewView()` or from the retry timer.

The `newViewAccumulator.remove(view)` call was **removed** from `onNewView()` — the accumulator retains quorum messages so that retry-path calls to `triggerProposal()` can still find them and pass the quorum check. Old accumulator entries are cleaned up by `advanceView()` (views older than `curView - 5`).

### 3.5 Interaction with View Timeout

The retry only exists after quorum. It self-schedules every 500ms until a block is proposed or the view changes:

```
View V starts (timeoutMs = 4000ms)
  resetTimer()
    → viewTimer at 4000ms (onTimeout)
    → no blockProposalTimer (only scheduled on-demand)

onNewView() quorum arrives (~50ms)
  → highQC set
  → triggerProposal()
  → buildBlock() empty
  → scheduleRetry()  ← timer first appears here
  → return

500ms later: blockProposalTimer fires → triggerProposal()
  → quorum check passes (accumulator still has messages, highQC set) ✓
  → buildBlock() empty again
  → scheduleRetry() → return  ← self-reschedules

500ms later: blockProposalTimer fires → triggerProposal()
  → buildBlock() has txs now!
  → propose ✓
  → cancelBlockProposalTimer() ✓

OR:
  → view timeout fires (4000ms) → advanceView() → cancelTimer() → cancelBlockProposalTimer() ✓
```

The retry cannot fire before quorum because it's only created inside `triggerProposal()`, which only runs after quorum is reached.

### 3.6 Files Changed

| File | Change |
| :--- | :--- |
| `server/.../server/consensus/Consensus.java` | Added `PROPOSAL_RETRY_MS` constant, `blockProposalTimer` field, `scheduleRetry()` method, `cancelBlockProposalTimer()` method. `triggerProposal()` calls `scheduleRetry()` on empty block and `cancelBlockProposalTimer()` on success. `resetTimer()` no longer schedules the retry. Removed `newViewAccumulator.remove(view)` from `onNewView()`. |

---

## 4. Current Pipeline State

After these changes, the end-to-end pipeline is:

```
Client sends Transaction
  → EnvelopeFactory.wrap(tx) → bytes
  → LinkManager.broadcast(bytes)

Server receives
  → LinkManager.receiveLoop() → DataMessage.payload (Envelope bytes)
  → MessageListener.onMessage(senderId, envelopeBytes)
  → MessageRouter: Envelope.parseFrom(bytes) → switch getPayloadCase()
  → TRANSACTION → service.addPendingTx(senderId, tx) → mempool

NEW-VIEW quorum arrives at leader
  → onNewView() sets highQC, calls triggerProposal()
  → quorum check passes ✓
  → buildBlock() returns EMPTY (Service.buildBlock() is a stub)
  → scheduleRetry() → return

500ms later: blockProposalTimer fires → triggerProposal()
  → quorum check passes ✓ (accumulator still has messages, highQC set)
  → buildBlock() still returns EMPTY
  → scheduleRetry() → return (self-reschedules)

View timeout
  → advanceView() → cancelTimer() → cancelBlockProposalTimer() → next leader

Server sends consensus messages
  → ConsensusMessage built
  → EnvelopeFactory.wrap(msg) → bytes
  → LinkManager.broadcast(bytes) or linkManager.send(peerId, bytes)
```

The plumbing is complete. The next step is implementing `Service.buildBlock()` to pull from the mempool and return a real block, which will unblock the entire consensus pipeline.

---

## 5. What Remains

| Component | Status | Notes |
| :--- | :--- | :--- |
| Envelope wire protocol | **Done** | `Envelope` proto + `EnvelopeFactory` + exhaustive switch router |
| Message routing | **Done** | `MessageRouter` with deterministic envelope-based dispatch |
| Consensus onMessage | **Done** | Accepts parsed `ConsensusMessage`, no double parse |
| Proposal retry timer | **Done** | Reschedule at half view timeout, quorum guard in `triggerProposal()` |
| Service mempool | **Done** | `addPendingTx()` + `drainMempool()` |
| `Service.buildBlock()` | **Stub** | Returns empty block. Needs to call `drainMempool()` and build a `Block` with transactions. |
| `Service.validateBlock()` | **Stub** | Returns `true`. Needs tx signature validation, nonce checking, gas limit verification. |
| `Service.validateTx()` | **Stub** | Returns `true`. Needs ECDSA signature verification, nonce check, balance check. |
| `Service.onDecide()` | **Partial** | Adds block to blockchain list but does not execute transactions. |
| Client Library | **Not updated** | Still sends `ClientRequest`, not `Transaction`. Needs `EnvelopeFactory.wrap(tx)`. |
| EVM Integration | **Not started** | Hyperledger Besu not integrated. |
| Genesis Block | **Not loaded** | `genesis.json` exists but nodes don't load it on startup. |
