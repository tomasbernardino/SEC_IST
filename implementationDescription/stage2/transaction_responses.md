# Transaction Response Flow

This document describes how DepChain handles transaction feedback to clients. All response logic is centralized in the `TransactionManager` to maintain a clean separation between consensus, execution, and client communication.

## 1. Immediate Rejection (Admission Check)

When a client submits a transaction, the `MessageRouter` passes it to the `TransactionManager`, which performs an immediate validation.

- **Trigger**: `MessageRouter` receives a `TRANSACTION` envelope and calls `transactionManager.addPendingTx(senderId, tx)`.
- **Validation**: `TransactionManager` performs:
    - Intrinsic gas check.
    - Account existence and type check.
    - Nonce check (must be $\ge$ current account nonce).
    - Sufficient funds check.
    - ECDSA Signature verification.
- **Response**: If validation fails (or if the transaction is a duplicate), the `TransactionManager` immediately sends a `TransactionResponse` back to the client via its internal `LinkManager` reference.

## 2. Origin Mapping

To ensure that only the node that received the transaction from the client replies, the `TransactionManager` maintains a local mapping:

- **Storage**: `txHashToSenderId` map (Process ID).
- **Behavior**: When a valid transaction is admitted to the mempool, the server records the `senderId`. This mapping is local and represents the "waiting origin". By storing this locally, we prevent duplicate responses from other nodes that receive the transaction via consensus.

## 3. Final Receipt (Post-Execution)

After consensus and execution, the `Service` triggers the final response flow.

- **Trigger**: `Service.onDecide()` is called after a block reaches finality.
- **Execution**: The `TransactionExecutor` executes the transactions and returns a list of `TransactionResponse` objects.
- **Routing**:
    1. The `Service` calls `transactionManager.sendResponses(results)`.
    2. For each result, `TransactionManager` checks its local `txHashToSenderId` map.
    3. If a `senderId` is found (meaning *this* node was the entry point), it sends the result to the client.
    4. The mapping is removed.

## Summary of Responsibilities

- **MessageRouter**: Entry point for network bytes; delegates to `TransactionManager`.
- **TransactionManager**: Owns the `LinkManager` for client communication; handles both immediate rejections and final receipts; tracks transaction origins.
- **Service**: Orchestrates block execution and notifies `TransactionManager` when final results are ready.