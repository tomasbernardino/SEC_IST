# Step 5 Core Components: Byzantine Fault Tolerance with Threshold Signatures

Step 5 upgrades the HotStuff consensus from Step 3 (HMAC-based) to use **Threshold Signatures (Ed25519)**. This achieves true Byzantine Fault Tolerance by ensuring that a Quorum Certificate (QC) can only be formed if $n-f$ honest replicas interactively participate in a 2-round signing process.

| Component | Responsibility | What does it solve? |
| :--- | :--- | :--- |
| **`CryptoManager.java`** | Manages threshold keys and Ed25519 operations. | **Key Isolation**: Each node only holds its private share. It abstracts the complex `threshold-sig` library calls into simple Round 1/Round 2 methods. |
| **`ThresholdPKISetup.java`** | A "Dealer" utility for key generation. | **Trust Bootstrap**: Generates the $(t, n)$ polynomial, distributes private shares to nodes, and exports the aggregate public key. |
| **`Consensus.java` (Updated)** | Orchestrates the 2-round signing flow. | **Interactive Consensus**: Replaces the single-message vote with a stateful interaction (Ri points → Challenge → Scalar Shares). |
| **`QuorumCertificate.java` (Updated)** | Holds a single 64-byte signature. | **Efficiency**: Instead of $n-f$ individual signatures, it stores one aggregate signature, reducing message size and verification time. |

---

## 2. Key Concepts & Their Implementation

### 2.1 The 2-Round Threshold Signing Process
Unlike RSA or HMAC, Ed25519 threshold signatures are **interactive**. To sign a message (e.g., a Phase + View + NodeHash), the nodes must perform two rounds:

1.  **Round 1 (Commitment)**: 
    - Each replica generates a secret nonce $rs$ and sends the corresponding public point $R_i$ to the leader.
    - **Implementation**: Replicas store $rs$ in `rsMemory` in `Consensus.java`, keyed by View and Phase, to use in Round 2.
2.  **Round 2 (Response)**:
    - The Leader aggregates $n-f$ $R_i$ points into a global $R$. It broadcasts a `ChallengeMessage` containing $R$ and a challenge $k$.
    - Replicas use their private share and the remembered $rs$ to compute a scalar signature share $s_i$.
    - The Leader aggregates $n-f$ $s_i$ shares into the final signature $(R, s)$.

### 2.2 Threshold PKI (Public Key Infrastructure) Layout
We use $(t=3, n=4)$ parameters. This means we need 3 out of 4 nodes to sign.
- **`keys/threshold_public.key`**: The aggregate public key used by everyone to verify QCs.
- **`keys/node-X-threshold.key`**: The private share unique to each node.

---

## 3. Protocol Message Changes (`consensus.proto`)

To support the interactive flow, we introduced:
- **`ChallengeMessage`**: Sent by the leader after Round 1. It contains the `aggregated_r`, the challenge `challenge_k`, and the list of `participating_nodes` indexes (needed for Lagrange interpolation).
- **`VoteMessage` Fields**:
    - `r_point`: Used in Round 1.
    - `scalar_signature`: Used in Round 2.
- **`QuorumCertificate`**: Now contains a single `bytes threshold_signature` instead of repeated fields.

---

## 4. Updates to `Consensus.java`

### 4.1 Dependency Injection for Testing
We refactored the constructor to allow passing a `CryptoManager`. 
- **Production**: `App.java` passes a real `CryptoManager` that loads keys from disk.
- **Testing**: `ConsensusTest.java` passes a **Mock** `CryptoManager` using Mockito. This allows us to simulate the 2-round flow and force "successful" signatures without needing real key files.

### 4.2 State Management
- **`voteAccumulator`**: Now handles two sub-types of votes: `phase + "-R1"` and `phase + "-R2"`.
- **`onVote` Logic**: 
    - If R1 quorum reached → Broadcast `ChallengeMessage`.
    - If R2 quorum reached → Form `QuorumCertificate` and advance to the next phase.

---

## 5. Verification & Testing

### 5.1 Mocking the Crypto Layer
In `ConsensusTest.java`, we don't use real threshold signatures because they are computationally expensive and require precisely coordinated nonces. Instead:
- We mock `cryptoManager.verify()` to return `true`.
- we mock `aggregateSignatureShares()` to return a dummy 64-byte array.
- This allows us to verify that the **Protocol Logic** (message ordering, phase transitions, leader rotation) is correct.

### 5.2 How to Run
```bash
# Compile everything including the new protobufs
mvn clean install -DskipTests 

# Run the consensus tests
mvn -pl server test -Dtest=ConsensusTest
```

### 5.3 Key Test: `testHappyPathLeaderCompletesOneView`
This test is the "Gold Standard" for Step 5. It manually feeds Round 1 votes from "node-1" and "node-2" to the leader, verifies a `ChallengeMessage` is emitted, then feeds Round 2 shares and verifies the leader progresses to the next phase.
