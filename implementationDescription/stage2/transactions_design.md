# Stage 2 Transactions Design

This document outlines the design decisions and rationale for the transaction objects implemented in Stage 2 of DepChain, detailing how they map to the Ethereum specification and the specific adaptations made for this project.

## 1. Overview

The DepChain transaction system is deeply inspired by Ethereum's model, enabling:
- Native cryptocurrency transfers (DepCoin)
- Smart contract deployment
- Smart contract execution (function calls)
- Gas-based transaction fees

To achieve this, the generic `ClientRequest` and `ClientResponse` messages from Stage 1 have been completely replaced with dedicated `Transaction` and `TransactionResponse` protobuf messages.

## 2. The `Transaction` Object

The transaction object mirrors Ethereum's transaction structure with necessary simplifications suited for a university project and constraints imposed by Stage 1 choices (e.g., using RSA cryptography).

### 2.1 Mapped Fields

The following fields behave identically or analogously to their Ethereum counterparts:

- **`to` (`string`)**: The recipient address. Just like in Ethereum, if this field is left empty, the transaction is interpreted as a **smart contract deployment**.
- **`nonce` (`uint64`)**: A per-account sequential counter. It serves the exact same purpose as in Ethereum: preventing replay attacks and ensuring transaction ordering for a given sender.
- **`data` (`bytes`)**: Contains EVM calldata for contract calls, or EVM initialization bytecode for contract deployments.
- **`gas_limit` (`uint64`)**: The maximum number of gas units the sender is willing to spend for the transaction execution. The fee formula remains `min(gas_price * gas_limit, gas_price * gas_used)`.

### 2.2 Deviations from Ethereum

Several fields deviate from standard Ethereum transactions. These decisions were made to address the project's specific requirements or to simplify the implementation:

| Field | Our Design | Ethereum Equivalent | Rationale for Deviation |
| :--- | :--- | :--- | :--- |
| **`from`** | `string from` | Implicitly recovered | In Ethereum, the sender's address is mathematically recovered from the ECDSA signature (`ecrecover`). DepChain uses **RSA**, which does not support key recovery. Therefore, the sender's address must be explicitly provided in the payload and verified against their known public key. |
| **`value`** | `uint64 value` | `uint256 value` (Wei) | Ethereum uses 256-bit integers to prevent overflow when handling 18 decimals. DepChain's scope allows using a standard `uint64` for the native DepCoin amount, avoiding the serialization complexity of `BigInteger` over protobuf. |
| **`gas_price`** | `uint64 gas_price` | `maxFeePerGas` / `gasPrice` | We implement the simpler "legacy" gas model with a single `gas_price` explicitly chosen by the user, rather than the more complex EIP-1559 model (base fee + priority fee tip), as the project guidelines suggest a straightforward fee structure. |
| **`signature`** | `bytes signature` | `v`, `r`, `s` (ECDSA) | DepChain uses an RSA signature over the protobuf serialization of the other fields, instead of the standard Ethereum cryptographic curve (`secp256k1`). |
| **`chainId`** | _Omitted_ | `uint256 chainId` | Ethereum uses `chainId` strictly for cross-chain replay protection (EIP-155). DepChain is a single permissioned chain, making this field redundant. |
| **`type`** | _Implicit_ | `uint8 type` | Originally considered, but we realized Ethereum dynamically infers the transaction type from the `to` and `data` fields. We adopted this implicit inference logic to reduce payload redundancy. |

***

## 3. The `TransactionResponse` Object

The `TransactionResponse` serves as a simplified equivalent to an **Ethereum Transaction Receipt**, returned to the client once consensus is reached and the execution is finalized.

### 3.1 Included Fields

- **`transaction_hash` (`bytes`)**: The SHA-256 hash of the original `Transaction`. Because we removed the `timestamp` field from the client request (as it is not present in Ethereum), clients use this hash to match incoming responses to their pending requests.
- **`block_number` (`uint64`)**: The height of the blockchain where the transaction was finalized.
- **`gas_used` (`uint64`)**: The exact amount of gas consumed by EVM execution, ultimately determining the final fee paid.
- **`return_data` (`bytes`)**: Raw EVM return data. For a standard call, this contains the function's return values. For a contract deployment, it contains the address of the newly deployed smart contract.

### 3.2 Deviations from Ethereum Receipts

| Ethereum Receipt Field | Our Design | Rationale for Deviation |
| :--- | :--- | :--- |
| **`status`** (`0` or `1`) | `TransactionStatus status` | We use an explicit enum (e.g., `SUCCESS`, `FAILURE`, `OUT_OF_GAS`, `INSUFFICIENT_FUNDS`) rather than a binary `0/1`. This drastically improves debuggability and logging for the project, and is safer than raw strings. |
| **`error_message`** | `string error_message` | We added an optional `error_message` string to provide human-readable details about failures, making debugging significantly easier than parsing raw EVM revert reasons. Ethereum encapsulates this in RPC errors or revert messages. |
| **`contractAddress`** | _In `return_data`_ | Ethereum includes a dedicated field for the created contract address. We pack this into `return_data` to keep the response generic. |
| **`logs`** (Events) | _Omitted_ | Ethereum emits event logs for clients. This adds significant complexity (and requires Bloom filters). Given the project's focus on fault tolerance rather than frontend dApp integration, event logging is considered out-of-scope. |
| **`logsBloom`** | _Omitted_ | Used to quickly search block logs. Unnecessary since we omit logs entirely. |
| **`cumulativeGasUsed`**| _Omitted_ | In our current model, returning the per-transaction `gas_used` is sufficient, as clients care more about their individual cost than the block's total gas consumption up to that point. |

## 4. Conclusion

By mapping closely to Ethereum while abstracting away unneeded production complexity (ECDSA, 256-bit math, EIP-1559, Bloom filters) and adapting to our existing architecture (RSA keys), we have created a robust, easily extensible transaction model. This enables the complete deployment and interaction lifecycle for the required frontrunning-resistant ERC-20 token in Stage 2.
