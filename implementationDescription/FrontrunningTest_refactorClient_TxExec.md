### 1. Interactive client improvements

- The client now exposes an interactive menu instead of only raw text commands.
- It supports:
  - free native DepCoin balance queries that gather `f + 1` node replies and keep the freshest state by block number;
  - native DepCoin transfers;
  - common IST Coin operations (`balanceOf`, `transfer`, `transferFrom`, `approve`, `increaseAllowance`, `decreaseAllowance`, `allowance`);
  - custom contract calls with raw calldata.
- Gas price and gas limit can be provided explicitly from the client UI.
- The client now validates gas inputs and basic hex/address formatting earlier, producing clearer user-facing errors.

### 2. Free native balance query

- Native DepCoin balance reads now use a dedicated request/response path instead of a paid transaction.
- The client broadcasts a balance request to all nodes, waits for `f + 1` replies, and chooses the response from the highest known block number.
- This keeps native balance lookup free while still preferring the most up-to-date state among the first quorum of replies.

### 3. TransactionManager cleanup and fixes

- `buildBlock()` now preserves the execution order selected by the sender-frontier algorithm.
  - This fixes a real bug: the previous final sort by gas price could reorder same-sender transactions and break nonce order inside the block.
- Validation logic was simplified into clearer phases:
  - transaction shape / gas checks;
  - sender existence and EOA checks;
  - nonce and balance checks;
  - signature verification.
- `validateBlock()` now also checks:
  - total block gas limit;
  - contiguous sender nonces inside the proposed block.

### 4. TransactionExecutor refactor

- Contract call and contract creation execution now share one common EVM execution path.
- Added tracer/revert handling logic so the error interpretation is concentrated in one place.
- Revert decoding is now easier to follow:
  - standard `Error(string)`;
  - the ERC-20 important custom errors are also handled.


### 5. frontrunning tests

- `FrontrunningTest` uses `SystemSetupTool` and the generated genesis state.
- Alice is mapped to `client-0`, which starts with the full IST Coin supply from genesis.
- Bob is mapped to `client-1`.
- The tests exercise a close to real execution Alice/Bob scenario:
  - one test reproduces the classic `approve(newValue)` frontrunning vulnerability;
- one test shows that the `increaseAllowance` + `decreaseAllowance` workflow bounds Bob to the amount already approved/spent.

