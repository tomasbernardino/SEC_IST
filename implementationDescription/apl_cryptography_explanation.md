# Inside the Authenticated Perfect Link (APL) and CryptoUtils

This document serves as the master reference for the Network Layer's cryptography and packet filtering logic. It heavily contrasts our implementation in the project against the flawed patterns found in previous years' projects to definitively justify our architectural choices.

---

## Part 1: Cryptographic Primitives [CryptoUtils.java]

We designed [CryptoUtils] to be a pure, highly optimized mathematical utility. It completely avoids the File System (KeyStore loading) because I/O operations strictly belong to the Application startup phase, not high-speed network math.

### 1. No "HexString" Bandwidth Bloating
**Old Projects:** Frequently converted cryptographic hashes and encrypted keys into Hexadecimal Strings (e.g., `"A3F9..."`) before injecting them into the Protobuf.
**Our Approach:** Hex strings physically double the required network bandwidth (2 chars = 1 byte) and waste CPU cycles on conversion. Because Protocol Buffers natively support raw `bytes`, we send pure binary arrays (`ByteString.copyFrom(byte[])`) directly over the UDP socket.

### 2. High-Speed Hashing (No Java Serialization)
To compute an HMAC that binds multiple fields together (like `SenderID + RecipientID + SequenceNumber + Payload`), you must feed all these bytes into the hash engine.
**Old Projects:** Used heavy Java Object Serialization (`ByteUtils.serialize(Object...)`) to cram everything into a massive byte array. This bloated the data with Java class metadata and thrashed the Garbage Collector.
**Our Approach:** We use a simple loop over Java varargs (`for (byte[] part : parts) { mac.update(part); }`). The `.update()` engine streams the tiny raw bytes directly into the C-backed SHA-256 math engine without ever instantiating a large concatenated array in memory. (`CryptoUtils.toBytes(String)` safely grabs the raw UTF-8 bytes to feed into this loop).

    
---

## Part 2: The APL Authentication Flow (The Handshake)

To satisfy **PL3 (Authenticity / No Creation)** without violating the "no secure channel (TLS)" project rule, we built a mathematically robust, Symmetric 2-Way Handshake that perfectly models modern PFS (Perfect Forward Secrecy).

### Key Pair Management: Static vs Ephemeral
**Old Projects:** Often used static DH keys or pre-encrypted a random AES key with the peer's RSA Public Key and sent it over the wire. This lacked Perfect Forward Secrecy; if an attacker stole the RSA key 5 years later, they could decrypt the old network traffic.
**Our Approach:** 
1. **`identityKeyPair` / `peerPublicKey` (RSA 2048):** Long-Term keys representing true identity. Used *only* to digitally sign the handshake.
2. **`dhKeyPair` (DH 2048):** Ephemeral keys. Generated completely randomly from scratch every time the node starts. This strictly enforces Perfect Forward Secrecy.

### The True 2-Way Handshake Flow
**Old Projects:** Their [HandshakeAck] only contained an HMAC. They never mathematically exchanged the second half of the Diffie-Hellman keys, making the protocol incredibly vulnerable to Man-in-the-Middle hijacking or replay attacks.

**Our Unbreakable Flow:**
1. **Initiator [startHandshake()]:** Node A sends its Ephemeral DH Public Key, digitally signed by its Long-Term RSA Identity Key.
2. **Responder [onHandshake()]:** Node B receives Node A's DH key. It verifies the RSA signature to mathematically prove it came from Node A. Node B now has both halves of the math and calls [establishSession()] to compute the AES `sessionKey`.
3. **Responder Replies:** Node B sends a [HandshakeAck] back to Node A. Crucially, Node B includes its *own* Ephemeral DH Public Key, digitally signed by Node B's Long-Term RSA Identity Key.
4. **Initiator Resolves [onHandshakeAck()]:** Node A receives the ACK. It verifies Node B's RSA signature. Node A extracts Node B's DH Public Key (`CryptoUtils.decodeDHPublicKey()`) and calls [establishSession()].

Both nodes have now arrived at the exact same AES `sessionKey` without ever transmitting the secret over the network!

### Preventing "Busy-Waiting" (The Send Buffer)
If the Application calls `apl.send(data)` while the Handshake is still executing, we push the payload into `ArrayList<byte[]> sendBuffer`. The Application thread returns instantly. The split-second the [establishSession()] call passes, the APL automatically flushes the buffer.
**Old Projects:** Froze the entire Application Thread in a `while(true) Thread.sleep(100)` loop (`checkHandshake()`), destroying node concurrency.

---

## Part 3: Packet Routing and Duplicate Filtering

### 1. `HANDSHAKE_SEQ` vs `sendSeqCounter`
A [StubbornLink] needs a unique sequence number to track packets for retransmission. 
We strictly isolate network setup from Application Data.
- **Application Data:** Uses `sendSeqCounter.getAndIncrement()`, generating `1, 2, 3...` to feed into the strict Duplicate Filter.
- **Handshakes:** Hardcoded to use `HANDSHAKE_SEQ = -1`. The Duplicate Filter completely ignores negative numbers, cleanly decoupling internal networking topology from Application messages.
*(Note: Because Handshake packets bypass the gap-tracking filter, we theoretically could receive infinite [HandshakeAck]packets if an attacker replays them. However, our [establishSession()] method is intentionally designed as **Idempotent** (`if (sessionKey != null) return;`). Any replayed handshakes are processed in instant $O(1)$ time and safely ignored without consuming any memory or logic cycles).*

### 2. Assuring PL2 (No Duplication) - Fixing the Memory Leak
**Old Projects (`project_previous_1`):** Tracked delivered sequence numbers by pushing every single one into a gigantic, infinite `HashSet<Long> delivered`. If the node ran for 5 hours, the HashSet consumed massive amounts of RAM and crashed the JVM.

**Our Approach (The High-Water Mark):**
UDP networks often deliver packets out of order (e.g., `1, 2, 4, 3`). We solve this using a mathematically elegant sliding window:
- `highestDeliveredSeq`: The highest contiguous number we've seen (starts at 0). Memory: $O(1)$.
- `nonDeliveredSeqs`: A `HashSet<Long>` capturing any skipped numbers (gaps). Memory: $O(dropped)$.

**The Logic:**
- Receive `1`, `2`: Update `highestDeliveredSeq = 2`.
- Receive `4`: Deliver it. Update `highestDeliveredSeq = 4`. Notice we skipped `3`, so push `[3]` into `nonDeliveredSeqs`.
- Receive `3` (Arrives late): It is less than `4`, so check the HashSet. It is inside! Deliver it and remove it from the HashSet.
- Receive `2` again (A duplicate!): It is less than `4`. Check the HashSet. It is *not* inside. Silently **Drop** the packet.


