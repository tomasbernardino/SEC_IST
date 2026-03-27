# DepChain Gas Mechanism Design

## 1. Fundamental Principles
DepChain employs a gas mechanism modeled after Ethereum to prevent Spam/DoS and fairly price computational resources. This ensures scalability and deterministic execution across the peer-to-peer network.

### Hard Constraints
- **Block Gas Limit**: 100,000 gas units. A single block cannot exceed this limit regardless of transaction count.
- **Intrinsic Gas (Baseline Costs)**:
  - **Base Fee**: 21,000 gas (Standard transaction overhead).
  - **Contract Creation Fee**: 32,000 gas (Applied when the `to` address is empty).
  - **Calldata Fee**: 4 gas per zero byte, 16 gas per non-zero byte.

## 2. Validation & Billing Flow

### Stage 1: Static Validation (Pre-Mempool)
Before a transaction is accepted into the mempool:
1. **Signature Verification**: Validates the cryptographic owner.
2. **Intrinsic Gas Check**: `tx.gasLimit >= calculateIntrinsicGas(tx)`. If too little gas is provided, the transaction is rejected immediately with NO cost.
3. **Liquidity Check**: `account.balance >= (tx.gasLimit * tx.gasPrice) + tx.value`.

### Stage 2: Execution & Dynamic Charging
During block processing:
- **Upfront Billing**: The node verifies the max fee.
- **Dynamic Metering**:
  - `gasUsed = Intrinsic + EVMOpcodeCosts`.
  - Formula: `OpcodeCosts = (Limit - TracerRemainingGas)`.
  - Final fee charged: `gasUsed * gasPrice`.
- **Out of Gas (OOG)**: If execution exceeds `gasLimit`, all state changes are reverted except the fee. The **Entire Gas Limit** is charged to the sender as a penalty.

## 3. Account Model (BlockchainAccount)
- **EOA (Externally Owned Account)**: Managed by secp256k1 keys. Features a `nonce` to prevent double-spending and replay attacks.
- **ContractAccount**: Holds EVM Bytecode and a persistent `storage` map. Charges gas for bytecode storage and state updates.

## 4. Implementation Details
- **TransactionExecutor**: Integrates Hyperledger Besu EVM.
- **GasTracer**: Custom `OperationTracer` that allows the system to accurately deduct execution costs.
- **Deterministic Billing**: All nodes calculate identical gas usage, ensuring the blockchain hash remains in sync.
