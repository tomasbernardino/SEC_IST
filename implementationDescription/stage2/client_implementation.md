# Stage 2: Client Implementation

This document covers the client-side implementation for the DepChain blockchain, including transaction construction, ECDSA signing, Envelope-based network communication, and response tracking.

## 1. Overview

The client is responsible for:

1. Constructing Ethereum-style `Transaction` protobuf messages
2. Signing them with ECDSA (secp256k1)
3. Broadcasting signed transactions to all server nodes
4. Tracking responses until an f+1 quorum of confirmations is reached
5. Providing a clean REPL interface for user interaction

## 2. Architecture

```
App.java (CLI)
  └── ClientLibrary.java (core logic)
        ├── LinkManager (network layer, from common)
        ├── ECKeyPair (blockchain identity)
        ├── AtomicLong nonce (per-client sequential counter)
        ├── ConcurrentHashMap futures (requestKey → CompletableFuture)
        ├── ConcurrentHashMap pendingRequests (requestKey → Set<nodeId>)
        └── ConcurrentHashMap txHashToReqKey (txHash → requestKey)
```

The client has **two key layers**:

- **Network identity** — RSA keypair (`.p12` file) used for authenticated network communication via `LinkManager` (DH key exchange + HMAC auth)
- **Blockchain identity** — ECDSA keypair (`.key` file, web3j serialized) used for signing transactions. The address is derived as `"0x" + Keys.getAddress(keyPair)` (Ethereum standard, 42-char hex)

## 3. Transaction Construction (`submitTransaction`)

### 3.1 Flow

```java
submitTransaction(to, value, data, timeoutSeconds)
```

1. **Nonce** — `AtomicLong.getAndIncrement()` ensures sequential, unique nonces per client instance
2. **Build unsigned Transaction** — sets `from`, `to`, `value`, `nonce`, `gas_price`, `gas_limit`, `data`
3. **Sign** — `CryptoUtils.ecSign(myBlockchainKeys, unsignedBytes)`:
   - Keccak-256 hashes the serialized unsigned transaction bytes
   - Signs the hash with `web3j.Sign.signMessage()` (secp256k1 ECDSA)
   - Returns `ClientSignature` with `v`, `r`, `s` components
4. **Set signature** — `sig_v`, `sig_r`, `sig_s` set on the protobuf
5. **Compute tx hash** — Keccak-256 of the full signed transaction bytes
6. **Track** — store in `futures`, `pendingRequests`, and `txHashToReqKey` maps
7. **Broadcast** — wrap in `Envelope` via `EnvelopeFactory.wrap(signedTx)` and send to all nodes
8. **Return** — `CompletableFuture` wrapped with `orTimeout` + cleanup on expiry

### 3.2 Default Values

| Field | Default | Rationale |
| :--- | :--- | :--- |
| `gas_price` | 1 | Minimal fee per gas unit |
| `gas_limit` | 21000 | Standard ETH transfer gas limit |
| `timeout` | 30 seconds | Configurable via overload |

### 3.3 Contract Calls

Contract deployments and calls use the same `submitTransaction` method:
- **Deploy:** `to = ""`, `data = EVM bytecode`
- **Call:** `to = contract_address`, `data = ABI-encoded calldata`
- **Transfer:** `to = recipient_address`, `data = null`

## 4. ECDSA Signing

### 4.1 Key Loading

```java
ECKeyPair blockchainId = CryptoUtils.loadECKeyPair(ecdsaKeyPath);
```

The `.key` file contains a web3j-serialized ECDSA keypair (secp256k1). `CryptoUtils.loadECKeyPair()` reads the file and deserializes via `Keys.deserialize()`.

### 4.2 Signing Process

```java
ClientSignature sig = CryptoUtils.ecSign(myBlockchainKeys, unsignedBytes);
```

`ecSign()` in `CryptoUtils.java`:
1. Keccak-256 hashes the input bytes
2. Calls `Sign.signMessage(hash, keyPair)` — web3j's secp256k1 signing
3. Returns `ClientSignature(v, r, s)` — a record wrapping the signature components

### 4.3 Address Derivation

```java
this.myAddress = "0x" + CryptoUtils.getAddress(this.myBlockchainKeys);
```

`CryptoUtils.getAddress()` calls `Keys.getAddress(keyPair)` which returns a 40-character hex string (without `0x` prefix). The client prepends `0x` for standard Ethereum address format.

### 4.4 Verification (server-side)

`CryptoUtils.ecVerify(signature, expectedAddress, parts)`:
1. Recovers the public key from the signature via `Sign.signedMessageToKey()`
2. Derives the address from the recovered public key
3. Strips `0x` from expected address and compares case-insensitively

## 5. Response Tracking

### 5.1 Data Structures

| Map | Key | Value | Purpose |
| :--- | :--- | :--- | :--- |
| `futures` | `address:nonce` | `CompletableFuture` | Await completion |
| `pendingRequests` | `address:nonce` | `Set<String>` | Track which nodes confirmed |
| `txHashToReqKey` | `ByteBuffer(txHash)` | `address:nonce` | Lookup request by response hash |

`ByteBuffer` is used as map key for `txHashToReqKey` because `byte[]` does not have value-based equality in Java — `ByteBuffer.equals()` compares contents.

### 5.2 Quorum Logic

```java
quorumSize = f + 1  (where f = nodes.size() / 3)
```

For n=4, f=1, quorum=2. When f+1 different nodes send a `TransactionResponse` with matching `transaction_hash`, the future is completed.

This quorum is by design — the server-side consensus already ensures that decided blocks are valid (2f+1 votes), so f+1 matching responses from different nodes guarantees at least one correct node confirmed.

### 5.3 Response Flow

```
Server node sends TransactionResponse (wrapped in Envelope)
  → ClientLibrary.onMessage(senderId, envelopeBytes)
  → Envelope.parseFrom(payload) → getPayloadCase() == TRANSACTION_RESPONSE
  → Extract TransactionResponse
  → Lookup requestKey by txHash
  → Add senderId to confirmers set
  → If confirmers.size() >= quorumSize:
      → Remove from all 3 maps
      → future.complete(response)
```

### 5.4 Timeout + Cleanup

The returned future is wrapped with:

```java
return future
        .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .whenComplete((resp, ex) -> {
            if (ex instanceof TimeoutException) {
                futures.remove(requestKey);
                pendingRequests.remove(requestKey);
                txHashToReqKey.remove(txHashKey);
            }
        });
```

- `orTimeout` — throws `TimeoutException` after the configured seconds
- `whenComplete` cleanup — on timeout, removes all tracking state from all 3 maps
- On normal completion (quorum reached), maps are already cleaned up by `onMessage()`

## 6. App.java — CLI

### 6.1 Startup

```
Usage: App <clientId> <hosts.config> <keysDir> <password>
```

1. Loads node addresses from `hosts.config` via `ConfigReader.parseHosts()`
2. Loads RSA identity keypair from `<clientId>.p12` via `KeyStoreManager`
3. Loads all node public keys from `<nodeId>.p12`
4. Loads ECDSA blockchain keypair from `<clientId>.key` via `CryptoUtils.loadECKeyPair()`
5. Creates `ClientLibrary` and starts it

### 6.2 Commands

| Command | Syntax | Example |
| :--- | :--- | :--- |
| `transfer` | `transfer <to_address> <amount>` | `transfer 0xabc123... 100` |
| `contract` | `contract <contract_address> <hex_data>` | `contract 0xdef456... a9059cbb...` |
| `exit` | `exit` | — |

Both commands block on `future.get()` until the transaction is confirmed or times out.

### 6.3 Output

- **Success:** `Success! Block: 5 | Gas Used: 21000`
- **Failure:** `Transaction failed: FAILURE - : <error_message>`
- **Timeout:** `Request failed: java.util.concurrent.TimeoutException`

## 7. Files

| File | Description |
| :--- | :--- |
| `client/.../client/ClientLibrary.java` | Core client logic — transaction construction, signing, broadcast, response tracking |
| `client/.../client/App.java` | CLI entry point — key loading, REPL, command dispatch |
| `client/pom.xml` | Dependencies: `depchain-common`, `web3j:crypto:5.0.0` |
| `common/.../crypto/CryptoUtils.java` | ECDSA signing, verification, keccak hashing, key loading |
| `common/.../crypto/ClientSignature.java` | Record wrapping `(v, r, s)` signature components |
| `common/.../network/EnvelopeFactory.java` | `wrap(Transaction)` — wraps outgoing transactions in `Envelope` |
| `common/.../proto/client.proto` | `Transaction`, `TransactionResponse`, `TransactionStatus` definitions |

## 8. Initial State (Before Fixes)

The initial client implementation had three issues related to the Envelope wire protocol:

### 8.1 Issue 1 — No Envelope wrapping on send

The initial code base64-encoded the signed transaction and sent the base64 string as raw bytes:

```java
// Initial (broken):
String b64Tx = Base64.getEncoder().encodeToString(txBytes);
linkManager.broadcast(b64Tx.getBytes(StandardCharsets.UTF_8));
```

The server's `MessageRouter` expects `Envelope`-wrapped payloads. A base64 string fails `Envelope.parseFrom()` and is dropped.

**Fix:**
```java
linkManager.broadcast(EnvelopeFactory.wrap(signedTx));
```

### 8.2 Issue 2 — No Envelope unwrapping on receive

The initial code tried to parse the incoming payload directly as `TransactionResponse`:

```java
// Initial (broken):
TransactionResponse response = TransactionResponse.parseFrom(payload);
```

When the server sends responses wrapped in `Envelope`, this parse fails because the bytes are an `Envelope`, not a raw `TransactionResponse`.

**Fix:**
```java
Envelope env = Envelope.parseFrom(payload);
if (env.getPayloadCase() != Envelope.PayloadCase.TRANSACTION_RESPONSE) {
    LOG.warning("[Client] Unexpected envelope payload from " + senderId + ": " + env.getPayloadCase());
    return;
}
TransactionResponse response = env.getTransactionResponse();
```

### 8.3 Issue 3 — Unnecessary base64 encoding

The base64 encoding was a leftover from the Stage 1 string-based protocol. Protobuf bytes are already a binary format — base64 adds ~33% overhead for no reason.

**Fix:** Removed `Base64` and `StandardCharsets` imports. Broadcasting raw protobuf bytes wrapped in `Envelope`.

### 8.4 Issue 5 — No timeout on pending futures

The initial `CompletableFuture` had no timeout — if a transaction never reached consensus (e.g., network partition), the future hung forever.

**Fix:** Added `orTimeout(timeoutSeconds, TimeUnit.SECONDS)` wrapper with a default 30-second timeout. Added an overload `submitTransaction(to, value, data)` that defaults to 30 seconds.

### 8.5 Issue 6 — txHashToReqKey grows unbounded

The initial code only removed entries from `txHashToReqKey` when quorum was reached. Failed/timed-out transactions left stale entries forever — a memory leak.

**Fix:** Added `whenComplete()` cleanup on timeout:

```java
.whenComplete((resp, ex) -> {
    if (ex instanceof TimeoutException) {
        futures.remove(requestKey);
        pendingRequests.remove(requestKey);
        txHashToReqKey.remove(txHashKey);
    }
});
```

When the future times out, all 3 tracking maps are cleaned up. When the future completes normally (quorum reached), the maps are already cleaned up by `onMessage()`.
