# Step 2: Smart Contract execution (Besu/lab2)

This step focuses on implementing and testing the core business logic of the DepChain blockchain via Solidity smart contracts, executed by the Hyperledger Besu EVM.

## Implementation Details

### 1. IST Coin Contract (`ISTCoin.sol`)
The `ISTCoin` contract is a standard ERC-20 token with some improvements to prevent frontrunning.

-   **Requirement**: Prevent the "Approval Frontrunning" race condition where a spender can consume both the old and new allowance if the owner tries to change it in a single step.
-   **Solution**: We implement two layers of protection:
    1.  **Relative Updates**: We've added `increaseAllowance(spender, addedValue)` and `decreaseAllowance(spender, subtractedValue)`. These are the recommended way to manage allowances as they are inherently immune to the race condition.
    2.  **`approve` Guard**: The standard `approve` function is overridden to require that the current allowance be zero before setting a new non-zero value. This forces legacy clients into a safe "reset to zero" path.

### 2. Besu EVM Integration
The server now includes the **Hyperledger Besu EVM** library as a dependency. This allows the blockchain replicas to:
-   Maintain a deterministic `WorldState`.
-   Execute smart contract bytecode in response to client transactions.
-   Charge gas (DepCoin) for execution to prevent DoS attacks.

### 3. Verification
A standalone test suite (`ISTCoinTest.java`) has been created to verify:
-   Contract deployment and initial supply.
-   Deterministic execution of `transfer` and `transferFrom`.
-   Rejection of unsafe `approve` calls and success of relative allowance updates.

## Commands

### Compile Smart Contracts
The Solidity contracts are stored in `server/src/main/solidity/contracts`. To compile them and generate Java wrappers/bytecode:

```sh
mvn -pl server web3j:generate-sources
```
