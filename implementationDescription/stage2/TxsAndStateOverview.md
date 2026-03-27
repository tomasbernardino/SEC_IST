# DepChain Architecture Design: Unified Transaction & State Management

## 1. Overview
The transition to Stage 2 moves DepChain from a simple command-based replication system to a **Transaction-Centric Blockchain Architecture**. This shift enables deterministic smart contract execution, gas-based resource management, and auditable persistence.

## 2. The Mempool: Strict Ordering & Reliability
Replaced the legacy flat queue with a **Two-Level Indexed Mempool**:
- **Structure**: `Map<String, TreeMap<Long, Transaction>>` (Sender -> Nonce).
- **Security**: Ensures strict nonce ordering per sender. Transactions cannot "jump" nonces (e.g., Nonce 2 cannot be picked before Nonce 1).
- **Reliability**: Transactions are **not removed** from the mempool until they are legally committed to a block and finalized. This prevents data loss during consensus failures or view changes. Even if the node restarts, the mempool can be reconstructed from the latest block state.

## 3. Prioritized Block Building
The Leader node selects transactions using a **Prioritized Selection Logic**:
1. **Nonce Compliance**: Picks the next executable nonce for each sender.
2. **Fee Prioritization**: Groups candidate transactions and prioritizes those with higher `gasPrice`.
3. **Block Gas Limit**: Enforces a total budget of **100,000 gas units** per block.
4. **Deterministic Building**: The `BlockchainState` provides the canonical `blockNumber` and `previousHash`, ensuring all nodes work on the same chain head.

## 4. Execution & State Transitions
- **TransactionExecutor**: Acts as the bridge between DepChain's world state and the Hyperledger Besu EVM.
- **Besu Integration**: Local state (balances, code, storage) is bridged into a `SimpleWorld`, executed, and the resulting changes (delta) are synced back.
- **Atomic Commits**: State transitions only happen on block finalization, ensuring the ledger remains consistent.

## 5. Auditable Persistence
Blocks are stored as structured JSON in `storage/block[N].json`:
- **Transparent Transactions**: Full `Transaction` proto messages serialized as JSON objects.
- **Detailed Receipts**: `TransactionResponse` objects including `gasUsed` and `returnData`.
- **State Snapshot**: Full snapshot of the world state (EOAs and Contract Storage) at that height.
