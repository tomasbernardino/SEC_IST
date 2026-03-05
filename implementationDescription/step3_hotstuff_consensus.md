# Step 3 Core Components

Everything is 

| Component | Responsibility | What does it solve? |
| :--- | :--- | :--- |
| **`HotStuffNode.java`** | Defines the "Block" structure. | **Data Integrity**: Creates the hash-chain where every node is cryptographically linked to its parent. Any change to an old command invalidates the entire chain. |
| **`QuorumCertificate.java`** | Bundles $(n-f)$ signatures. | **Proof of Consensus**: Serves as the immutable "evidence" that a super-majority agreed on a specific block or phase. |
| **`Consensus.java`** | The protocol state machine. | **Safety & Liveness**: Implements the 4-phase voting rules, view management, and the Pacemaker timeout logic. |
| **`Service.java`** | The application memory/state. | **Execution State**: Maintains the final "Blockchain" list of decided strings, independent of the consensus voting process. |
| **`App.java` Integration** | Orchestrates the layers. | **System Liveness**: Solves the circular dependency between the Network (LinkManager) and Consensus layers using the `AtomicReference` pattern. |

---

## 2. Key Concepts & Their Implementation

### 2.1 The Concept of a "View"
- **Definition**: A View is an epoch or a turn where one specific node is in charge.
- **Leader Selection**: We use a simple rule: `Leader = (View_number - 1) % Total Nodes`. This means for a 4-node cluster:
View 1: Node 0 is leader.
View 2: Node 1 is leader.
...and so on.
- **Purpose**: The view structure ensures that if one leader is slow or byzantine, the "Pacemaker" (timer) will eventually force everyone to move to the next view and try a different leader.

### 2.2 The "Genesis" Node
*   The initial empty state $\bot$.
*   **What it solves**: The **Bootstrap Problem**. The first real block (View 1) needs a parent to point to.
*   **Implementation**: We hardcode a "Genesis Node" (Block 0) with zero hashes. Every replica starts with its `lockedQC` and `prepareQC` set to this Genesis QC, allowing the consensus chain to start from a common root.

<br>

## 3. The 4-Phase Message Flow

For a command to be finalized, it must progress through:

1.  **PREPARE**: The Leader proposes a block. Replicas verify it via the `safeNode` predicate.
2.  **PRE-COMMIT**: Replicas see that 3/4 nodes accepted the proposal. They update their local `prepareQC`.
3.  **COMMIT**: Replicas see the Prepare proof. **They set their `lockedQC` here**, establishing a safety lock on this branch.
4.  **DECIDE**: Replicas see the final proof of the Commit phase and execute the command (upcall to `Service.java`).

<br>

## 4. Safety & Liveness: The Protocol "Brain"

### 4.1 The `safeNode` Predicate
*   **Safety Rule**: Replicas only vote for blocks extending from their `lockedQC`.
*   **Liveness Rule**: Replicas can "unlock" and follow a newer branch if the leader provides a QC with a higher view number than their current lock.

### 4.2 The Pacemaker
*   **Implementation**: If a view takes too long, the local `onTimeout()` fires. It doubles the timeout (**Exponential Backoff**) and moves to `nextView`, sending a NEW-VIEW message to the next leader. This ensures the system eventually reaches a "Synchronous" window where all nodes overlap long enough to finish a decision.

<br>

## 5. Verification & Testing

To ensure the implementation is correct, we use a comprehensive suite of unit tests. These tests "mock" the network layer, allowing us to feed messages directly into the consensus engine.

### 5.1 How to Run
```bash
mvn -pl server test
```

### 5.2 Key Test Cases
Each test in `ConsensusTest.java` verifies a specific property of the HotStuff protocol:

- **`testLeaderRotation`**: Confirms that every node agrees on the same leader for every view using the `(View - 1) % N` formula. This solves the **Coordination Problem**.
- **`testSafeNodeSafetyRule`**: Verifies that a replica correctly rejects a block if it "forks" from the branch it is currently locked on. This proves **Safety**.
- **`testSafeNodeLivenessRule`**: Confirms that a replica CAN switch to a different branch if the leader proves the new branch has higher support (a fresher QC). This proves **Liveness**.
- **`testHappyPath`**: Simulates a perfect 4-node run for a single command, walking through all 4 phases (Prepare → Pre-Commit → Commit → Decide). This proves the **State Machine** is correctly wired.
- **`testHashChaining`**: Confirms that every block is cryptographically linked to its parent, ensuring that old history cannot be tampered with. This proves **Data Integrity**.
