# DepChain - Highly Dependable Systems (Stage 1)

This repository contains the Stage 1 implementation of **DepChain**, a simplified permissioned blockchain system using the Basic HotStuff algorithm.

## Project Structure

The project is structured as a Maven multi-module project:
- `common/`: Contains shared network abstractions (Fair Loss Links, Stubborn Links, Authenticated Perfect Links), cryptography utilities and `LinkManager`.
- `server/`: Contains the core blockchain implementation including the HotStuff `Consensus` engine, `CryptoManager` and service layer.
- `client/`: Contains the basic client library to submit requests to the blockchain service.

## Generating Keys

Before running the system, generate the RSA keys for the cluster nodes:

```bash
cd keygen_script
./generate_keys.sh
```
This script creates public and private keys in the `keys/` directory.

## Compilation

To compile the entire project and resolve all dependencies, run:

```bash
mvn clean install
```

## Executing the Test Suite

To run the complete test suite:

```bash
mvn test
```

### Key Byzantine and Dependability Tests

The `ConsensusTest.java` suite implements several targeted tests to demonstrate how DepChain handles Byzantine behavior and network unreliability:

1. **`testByzantineLeaderWrongNodeHash`**: 
   - **Simulation**: A Byzantine leader attempts to broadcast a `PREPARE` message where the valid justification QC does not cryptographically match the payload (`nodeHash`) of the proposed command.
   - **Detection**: Replicas reject the message and refuse to cast a vote because the signature verification check fails on the mismatched payload.

2. **`testByzantineReplayOldValidVote`**:
   - **Simulation**: A malicious replica or network attacker intercepts a valid `VOTE` from a previous view (e.g., View 0) and attempts to replay it during a later view (e.g., View 1) to force a premature quorum.
   - **Detection**: The Consensus engine strict-checks the phase and view of the vote against the current expected view (`curView`), instantly dropping the stale vote.

3. **`testNetworkPartitionAndRecovery`**:
   - **Simulation**: A network partition prevents a leader from gathering a quorum of votes (simulating extreme message loss).
   - **Recovery**: The local `Pacemaker` triggers a timeout, forcing honest replicas to abort the view and broadcast a `NEW-VIEW` message, advancing the consensus protocol seamlessly.

4. **`testForgedQC` / `testDuplicateVoteSpammer`**:
   - **Detection**: Replicas reject quorum certificates with invalid threshold signatures and strictly count only unique votes per replica ID, preventing Byzantine replicas from spamming duplicates to achieve quorum.