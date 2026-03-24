# Genesis Block & Initialization FAQ

This document outlines several explicit design choices and EVM (Ethereum Virtual Machine) mechanics associated with DepChain's `genesis.json` creation process, addressing common assumptions and deviations from legacy projects.

## 1. Why doesn't the original JSON template have a `block_hash`?

In standard Ethereum specifications, a `genesis.json` file is meant to be the *input* for constructing the genesis block; it is not the block itself. Therefore, it does not mathematically require a `block_hash` field to exist in the configuration file.

When a fresh node boots, it traditionally parses the `state` dictionary, instantiates all accounts and balances into its EVM state tree, and then programmatically calculates the `Keccak-256` hashing of the assembled Block 0 to generate the official genesis `block_hash` in memory.

However, to align closely with previous DepChain projects (like `project_previous_2`) and simplify execution, our `SystemSetupTool` now explicitly performs this mathematical `Keccak-256` hash of the assembled `state` JSON internally during the generation pipeline, effectively stamping the calculated `block_hash` into the output file so nodes do not have to compute it dynamically.

## 2. Why don't the addresses in `genesis.json` start with `0x`?

Internal EVM tools and JSON state files represent Ethereum addresses and transaction hashes as raw 40-character hexadecimal strings without the `0x` prefix. This is done to maximize parsing speed and ensure data type consistency during deserialization.

The `0x` prefix is strictly a human-readable convention and is only used in configurations meant for human interaction (such as our `addresses.config`), user interfaces, and source code to differentiate hex-strings from decimal values.

## 3. What is the `storage` dictionary inside the Smart Contract?

This section implements our **Offline State Injection** mechanism to avoid writing fragile deployment scripts.

EVM smart contracts store global variables linearly in a massive array of 32-byte slots. Our `SystemSetupTool` mathematically calculates the exact target slots for our variables beforehand and injects them directly into the contract's initial memory allocation.
* Slot `"2": "0x2540be400"` targets the ERC-20 `_totalSupply` variable. It initializes it to 100,000,000 IST tokens (`0x2540be400` in hex).
* Slot `"a7a85c..."` is the Keccak-256 derived slot specifically allocated for `client-0`'s balance mapping in the `_balances` dictionary, initializing the client with the entire available token supply immediately upon launch.

## 4. What does the base `balance` field mean?

The `balance` field in `genesis.json` denotes the native base cryptocurrency of the DepChain network (analogous to actual "Ether" on Ethereum). This is entirely distinct and separate from the deployed `ISTCoin` ERC-20 token balance.

Every configured client is given an initial base balance of `100,000` units. This "gas money" ensures that they have the required base funds to pay the network transaction execution fees when submitting `ISTCoin` transfer transactions to the network. Smart contracts generally have a `balance` of `0` because they do not initiate outward transactions themselves and therefore do not pay gas.

## 5. Why do accounts start with `nonce: 0`?

In Ethereum models, nonces exist to prevent replay attacks by ensuring sequential transaction processing. If an account is created in the EVM without an explicitly stated nonce, its default value is automatically set to `0`. 

For extreme clarity, our `SystemSetupTool` explicitly adds `"nonce": 0` to user (EOA) accounts in the `genesis.json`.

## 6. Does the Smart Contract account need an explicit nonce in the genesis?

**No.** In the EVM, an account's nonce has different meanings depending on its type:
1. **For an EOA (User Wallet):** The nonce tracks the number of *transactions* sent by that user.
2. **For a Smart Contract:** The nonce strictly tracks the number of *child smart contracts* it has deployed using the EVM `CREATE` opcode.

Because `ISTCoin` is a standard ERC-20 token that simply manages balances and never dynamically deploys new sub-contracts under the hood, its nonce will literally never change. It is securely managed by the EVM's default starting values (which is `1` following EIP-161 rules) and does not need to be visibly defined or tracked within the `genesis.json`.
