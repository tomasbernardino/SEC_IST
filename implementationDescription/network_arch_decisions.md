# Step 2 Network Implementation: Architectural Decisions & Clarifications

This document summarizes the architectural choices, doubts clarified, and optimization decisions we discussed after the initial implementation of the project Step 2 network layer. These insights explicitly justify why the code deviates from pure abstract theory into heavily optimized practical engineering.

---

## 2. Receiving Architecture: Where is [deliver()] in [StubbornLink]?

**The Doubt:** The `deliver()` method is omitted in `StubbornLink`. How does the receive logic actually work without it?


**The Approach:** 
1. **Asymmetric Retransmission:** `StubbornLink`'s *only* theoretical job is retransmitting outgoing messages. It does absolutely no processing on incoming messages (duplicate filtering belongs to APL). Putting a `deliver()` method in SL would simply act as a useless pass-through.
2. **The LinkManager (Dispatcher):** We introduced a `LinkManager` that runs a single background loop on `fll.deliver()`. It parses the incoming packet into a typed `Message`, reads the `SenderID`, and directly routes the message upward to the `AuthenticatedPerfectLink` handling that specific peer. 

This creates a high-performance multiplexer that cleanly skips the useless SL step on the way up, while properly routing to mathematical cryptographic isolations on a per-peer basis.

---

## 3. Acknowledgment Semantics: How does `sendOnce()` satisfy "Stubborn" properties?

**The Doubt:** The `StubbornLink` guarantees reliable delivery via infinite retransmission. How does calling `sl.sendOnce(ACK)` (which does *not* retransmit) safely guarantee those properties?

**The Resolution:**
An ACK is implicitly retransmitted by the original sender's failure to receive it. 
1. Sender stubbornly resends `Data(seq=1)` every 500ms.
2. Receiver gets it, Drops it (as duplicate), but fires `sendOnce(ACK(1))`.
3. If the ACK is lost in the network, the Sender's timer is never cancelled.
4. The Sender will stubbornly resend `Data(seq=1)` again.
5. The Receiver will respond with another fresh `sendOnce(ACK(1))`.

This elegantly guarantees delivery of the ACK without creating infinite loops (where Sender retransmits `AckForAck` to cancel the Receiver's ACK timer) and without leaking timer threads.

---

## 4. Cryptographic Theory vs Implementation: The `recipient_id`

**The Doubt:** The mathematical property of a MAC (`authenticate(p, q, m)`) requires binding the sender ($p$), the recipient ($q$), and the message ($m$). But does the Protobuf *wire protocol* actually need to transmit the string "recipient_id"?

**The Resolution:** 
Mathematically, no! The receiver already knows its own identity. 
- **The Optimization:** We removed `string recipient_id = 2;` from `messages.proto` entirely, saving bandwidth on every UDP packet.
- **The Cryptographic Guarantee:** When Node A sends a packet to Node B, it computes `HMAC("Node A", "Node B", payload)`. When Node B receives it, Node B forces *its own identity* into the verification check: `verifyHmac("Node A", selfId, payload)`. 

If an attacker captures the packet and reflects it to Node C, Node C will attempt to verify it using `selfId = "Node C"`. The cryptographic hash will aggressively mismatch (because the original MAC was calculated using "Node B"). 

This implicit verification perfectly satisfies the Authenticated Perfect Link `PL3` authenticity properties while minimizing network overhead.

---

## 5. Identifying Nodes and Clients: `ProcessInfo` Strings

**The Doubt:** Why use Strings (e.g., `"node-0"`) instead of integers (`0`, `1`) for `sender_id`?
**The Resolution:**
While integers work for pure Node-to-Node consensus groups, Step 4 of the project introduces external Application Clients. 
If IDs are integers, it becomes virtually impossible to track dynamically disconnecting web clients. By using `String` across the entire Network Layer, we can cleanly distinguish static validators (`"node-0"`, `"node-1"`) from dynamic incoming transaction requests (`"client-UUID_1234"`). 

---

## 6. Decoupling and Configuration: `MessageListener` & `ProcessInfo`

**The Doubt:** What exactly do `MessageListener` and `ProcessInfo` do, and why were they implemented?

**`MessageListener` (Decoupling Network from Consensus):**
In previous projects, the network layer had direct Java imports for the `ByzantineConsensus` class. This is "Tight Coupling" — the network cannot be compiled or tested without a fake blockchain.
- **How it works:** `MessageListener` is a simple interface with one method: `onMessage(String senderId, byte[] payload)`. 
- **The Result:** The `LinkManager` simply accepts a `MessageListener` in its constructor. When a packet is fully authenticated, it calls `listener.onMessage()`. The Network Layer has zero idea what "HotStuff" is. When we build the Consensus Layer in Step 3, it will simply implement this interface, allowing true decoupled unit testing.

**`ProcessInfo` (Solving the IP-Parsing Problem):**
In previous projects, addresses were often passed as raw strings like `"192.168.1.5:8080"`, requiring the `FairLossLink` to continually call `String.split(":")` on every single packet just to figure out where to route it over UDP.
- **How it works:** `ProcessInfo` is an immutable Java `record(String id, InetAddress address, int port)`.
- `ProcessInfo` solves the IP-address-parsing problem. It is an immutable Java record that allows `FairLossLink` to map `"node-0"` to an `InetAddress` instantly without repeatedly parsing raw strings like `"192.168.1.5:8080"` on every packet.

---

## 7. The Multiplexer: `LinkManager`

**The Doubt:** What exactly is the role of `LinkManager`, and how did previous projects handle multiple peers?

**The Flawed Approach:**
In previous projects, the networking stack was tightly-coupled to the Application layer. Classes like `BlockchainMember.java` or `DepchainNode.java` directly instantiated arrays of `FairLossLink`s and `AuthenticatedPerfectLink`s right in the middle of their consensus startup logic. 
Worse, the Application thread itself had to manually track UDP packets and explicitly figure out which `AuthenticatedPerfectLink` instance a packet belonged to. This violates the Single Responsibility Principle and bloats the consensus logic.

**The Clean Approach (LinkManager):**
Our `LinkManager` acts as a pure **Multiplexer/Demultiplexer**.
1. **Encapsulation:** The Application layer (Step 3/4) creates exactly ONE `LinkManager` object and calls `start()`. That is it. The Application is completely completely blinded to the existence of `FairLossLink`, `StubbornLink`, or `AuthenticatedPerfectLink`.
2. **The Background Thread:** The `LinkManager` spawns a single daemon thread (`LinkManager-Receive`) that sits in an infinite loop exclusively calling `fll.deliver()`.
3. **Routing (Demultiplexing):** When an anonymous UDP packet arrives, it bubbles up to the `LinkManager`. The Manager parses the Protobuf envelope, reads `msg.getSenderId()`, and pushes the packet directly into the exact `AuthenticatedPerfectLink` instance assigned to that specific sender.
4. **Upward Delivery:** If the APL accepts the packet (valid HMAC, not a duplicate), the APL returns the raw application `byte[]`. The `LinkManager` catches this and smoothly fires `listener.onMessage(senderId, payload)`.

This guarantees that the Consensus Layer only ever sees authentic, deduplicated business logic, while the `LinkManager` silently juggles the mathematical isolations and UDP routing in the background.

---

## 8. LinkManager: Cardinality and Application Integration

**The Doubt:** Why is there only 1 FLL and 1 SL, but multiple APLs? 
**The Resolution:** This is due to cryptographic isolation and socket management:
- **1 FairLossLink:** Represents the physical UDP hardware socket on the machine (e.g., port 8080). A node only has one open port, so it only needs one object to listen to it.
- **1 StubbornLink:** Acts as a centralized infinite `Timer` thread. It efficiently manages a single global timer queue for all outgoing packets.
- **Multiple AuthenticatedPerfectLinks:** Represent the mathematical isolated state per peer. Node 1 gets a unique AES `sessionKey` and its own `highestDeliveredSeq` (Duplicate filter counter). Node 2 gets a completely different AES key and counter. The `LinkManager` routes the multiplexed physical socket (FLL) into these isolated abstract state machines (APLs).

**The Doubt:** Do we really need the `broadcast()` method?
**The Resolution:** The strict theoretical network abstractions (FLL, SL, APL) are entirely Point-to-Point. However, Step 3 (The HotStuff Consensus Algorithm) heavily relies on the Leader node sending `PREPARE` or `COMMIT` messages to *all* validators simultaneously. The `LinkManager.broadcast()` method is merely a clean, convenient `for`-loop built directly in the router to save the Consensus layer from having to write `for(peer: peers) { send() }` loops explicitly across its codebase.

**The Doubt:** Where is the Application message actually decoded?
**The Resolution:** The Network Layer (Step 2) is a pure "Stupid Pipe." It is completely OSI Layer decoupled. It strips the Protobuf Network envelope (Sequence numbers, Sender IDs, MACs) and hands raw `byte[]` payloads up to the Application via the `MessageListener`.
The decoding happens entirely in the Application Layer (Step 3). The Application receives `byte[] payload` and executes `ConsensusMessage.parseFrom(payload)` to analyze the business logic (Votes, Blocks, client requests).

**The Doubt:** What does the Application Layer need to do to send and receive messages?
**The Resolution:** The Application layer simply instantiates the `LinkManager`, passes it a `MessageListener`, and calls `linkManager.start()`.
- **To Receive:** The Application implements the `onMessage(String senderId, byte[] payload)` callback interface. It inherently trusts that any bytes arriving here are authentic, temporally sequenced, and deduplicated.
- **To Send:** The Application serializes its `ConsensusMessage` into `byte[]` and simply calls `linkManager.send(recipientId, payload)`. The LinkManager seamlessly passes the bytes downward to the correct APL for AES Session MACing, duplicate tracking, and Stubborn retransmission.
