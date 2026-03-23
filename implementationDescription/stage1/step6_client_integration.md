# Step 6: Client Library & Blockchain Integration Analysis


### Request Construction & Signing (`ClientLibrary.java`)
When a user types a command (e.g., `> hello world`) in the terminal, execution begins in `App.main()`, which calls `ClientLibrary.append()`. 
- **Protobuf Creation**: The `append()` method builds a `ClientRequest` protobuf object (defined in `client.proto`).
- **Cryptographic Signing**: It calls `CryptoUtils.sign()` using the client's RSA private key (`myKeys.getPrivate()`) to sign the operation type, value, and a monotonic timestamp. This signature is attached to the protobuf payload.
- **Broadcast**: It calls `linkManager.broadcast()` to send the request to all server nodes over UDP. A `CompletableFuture` is created and stored in `futures` to block the client until consensus is reached.

### Dynamic APL Handshake (`LinkManager.java` & `FairLossLink.java`)
Because clients are not pre-configured as peers in the server's `hosts.config`, they must be discovered dynamically.
- **Packet Reception**: The server's `LinkManager.receiveLoop()` calls `FairLossLink.deliver()`, which returns a `ReceivedPacket` containing both the payload and the sender's actual network `InetAddress` and port.
- **Dynamic Registration**: If the sender ID (`client-0`) is unknown but its public key is loaded in `peerPublicKeys`, `LinkManager` calls `fll.registerPeer(...)` to map the client's ID to its real network address.
- **APL Creation**: It then instantiates a new `AuthenticatedPerfectLink` for the client. Because the network address is registered, the server can successfully route the Ephemeral Diffie-Hellman `HANDSHAKE_ACK` back to the client, establishing a secure session key.

### Admission & Buffering by Leader (`Consensus.java`)
Once the APL delivers the data payload, `LinkManager` passes it to `Consensus.onMessage()`.
- **Parsing**: `onMessage()` attempts to parse the payload as a `ClientRequest`.
- **Verification**: It verifies the RSA signature using `CryptoUtils.verify()` and the client's public certificate. It also verifies the monotonic timestamp to prevent replay attacks.
- **Buffering**: If valid, the request is wrapped in a `ClientRequestEntry` and placed into the thread-safe `clientRequestBuffer.add(entry)`. The method then calls `triggerProposal()`.

### Proactive Proposals & Liveness (`Consensus.java`)
The system employs a reactive-proposal mechanism to maximize liveness and prevent unnecessary dummy blocks.
- **Idling**: In `onNewView()`, if a quorum of `NEW-VIEW` messages is reached but the `clientRequestBuffer` is empty, the leader caches the current `QuorumCertificate` into `pendingHighQC` and waits.
- **Instant Trigger**: The moment a client request is buffered in step 3, `triggerProposal()` executes. It checks if it is the leader, retrieves the exact `pendingHighQC` saved earlier, pops the request from the buffer, and creates a new `HotStuffNode`. It immediately broadcasts a `PREPARE` message.

### Consensus Execution & Threshold Signatures (`CryptoManager.java`)
Replicas execute the 4-phase HotStuff protocol (`PREPARE`, `PRE-COMMIT`, `COMMIT`, `DECIDE`).
- **Partial Shares**: At each phase, replicas validate the node and generate a `SigShare` using `CryptoManager.signPartial(...)` and their private threshold key share.
- **Aggregation**: The leader collects these `voteAccumulator` messages. Once $k=3$ valid shares are acquired, it calls `CryptoManager.combineShares(...)` to generate a single, compact $O(1)$ RSA master signature. This becomes the `QuorumCertificate` attached to the next phase.

### Execution & Reply (`Service.java`)
Once a block achieves a `COMMIT` QC, the server finalizes the block.
- **Upcall**: `Consensus.onDecide()` invokes the `listener.onDecide()` callback, which is implemented by `Service.onDecide()`.
- **Storage**: `Service` adds the command to its persistent `blockchain` log and prints `"[Service] Command decided: hello world"`.
- **Response**: The `Service` constructs a `ClientResponse` protobuf and transmits it specifically to the client using `linkManager.send(response.toByteArray(), clientReq.getClientId())`.

### Quorum Finality (`ClientLibrary.java`)
Back on the client side, responses arrive via the client's own `LinkManager` and are passed to `ClientLibrary.onMessage()`.
- **Quorum Collection**: The client extracts the value and adds the `senderId` to the `Set` in `pendingRequests`.
- **Unblocking**: Once the set reaches `quorumSize` (which is $f+1$, locally calculated as `nodes.size() / 3 + 1`), it guarantees that at least one honest node executed the command. The client resolves the `CompletableFuture.complete(null)`, unblocking the terminal and allowing the user to type the next command.


## Running Automated Tests

To verify the entire consensus logic (including Step 6 integration), run the server module tests:

```bash
mvn test -pl server
```



## Running Manual Tests

To test the system manually with multiple nodes and a client, follow these steps. 

### Start the Nodes (Replicas)
Open 4 terminals and run one node in each. Replace `<password>` with yours (default is `sec_project_keys`).

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

### Start the Client
Open a 5th terminal and run the client:

```bash
mvn exec:java -pl client -Dexec.mainClass="ist.group29.depchain.client.App" -Dexec.args="client-0 hosts.config keys <password>"
```

### Submit Requests
In the client terminal, type a command and press Enter:
```
DepChain Client 'client-0' ready.
> hello world
Appending to blockchain...
Success: Consensus reached.
```

Check the node terminals to see the HotStuff phases (PREPARE, PRE-COMMIT, etc.) and the final `[Service] Command decided: hello world` log.
