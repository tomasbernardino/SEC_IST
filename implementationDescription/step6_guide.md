# Step 6 Implementation Guide & Testing Instructions

This document explains the changes made for Step 6 (Client Integration) and provides instructions on how to run both automated and manual tests.

## 1. Summary of Changes

### Core Logic
- **Consensus.java**:
    - Added a `clientRequestBuffer` (ConcurrentLinkedQueue) to store verified client requests.
    - Implemented onClientRequest which verifies the client's RSA signature using public keys provided by LinkManager.
    - Modified onNewView (Leader logic) to poll the buffer. If a client request is available, it proposes it; otherwise, it proposes a default idle command.
    - Fixed a critical liveness bug where the leader was not triggering its own onNewView quorum check.
- **Service.java**:
    - Fixed the onDecide upcall to actually store the decided command in the `blockchain` list.
- **ClientLibrary.java**:
    - Updated `submitRequest` to sign the ClientRequest protobuf using the client's private key.

### Verification
- **ConsensusTest.java**:
    - Fixed a bug in the determinism test where mismatched parameters caused failure.
    - Verified all 23 tests pass, covering Algorithm 2 end-to-end.

---

## 2. Running Automated Tests

To verify the entire consensus logic (including Step 6 integration), run the server module tests:

```bash
mvn test -pl server
```

You should see:
`Tests run: 23, Failures: 0, Errors: 0, Skipped: 0`

---

## 3. Running Manual Tests

To test the system manually with multiple nodes and a client, follow these steps. 
*Note: Ensure you have already generated keys using the provided scripts and that your [hosts.config](file:///home/tomas/IST/SEC/Project/SEC_IST/hosts.config) is correctly configured.*

### Step A: Start the Nodes (Replicas)
Open 4 terminals and run one node in each. Replace `<password>` with yours (default is sec_project_keys).

**Terminal 1 (node-0):**
```bash
mvn exec:java -pl server -Dexec.mainClass="ist.group29.depchain.server.App" -Dexec.args="node-0 hosts.config keys <password>"
```

**Terminal 2 (node-1):**
```bash
mvn exec:java -pl server -Dexec.mainClass="ist.group29.depchain.server.App" -Dexec.args="node-1 hosts.config keys <password>"
```

**Terminal 3 (node-2):**
```bash
mvn exec:java -pl server -Dexec.mainClass="ist.group29.depchain.server.App" -Dexec.args="node-2 hosts.config keys <password>"
```

**Terminal 4 (node-3):**
```bash
mvn exec:java -pl server -Dexec.mainClass="ist.group29.depchain.server.App" -Dexec.args="node-3 hosts.config keys <password>"
```

### Step B: Start the Client
Open a 5th terminal and run the client:

```bash
mvn exec:java -pl client -Dexec.mainClass="ist.group29.depchain.client.App" -Dexec.args="client-0 hosts.config keys <password>"
```

### Step C: Submit Requests
In the client terminal, type a command and press Enter:
```
DepChain Client 'client-1' ready.
> hello world
Appending to blockchain...
Success: Consensus reached.
```

Check the node terminals to see the HotStuff phases (PREPARE, PRE-COMMIT, etc.) and the final `[Service] Command decided: hello world` log.
