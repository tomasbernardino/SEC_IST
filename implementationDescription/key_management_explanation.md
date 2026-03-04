# Key Management & Node Bootstrapping (Stage 1)

This document explains the logic, architecture, and design decisions behind the Key Management and Configuration implementation for DepChain.

## 1. The Strategy: Offline Keystore Generation

According to the project guidelines, Stage 1 requires **Static Membership** and a **pre-distributed Public Key Infrastructure (PKI)**.

To achieve this, we departed from the previous projects' approaches:
- **`project_previous_1`** generated raw Base64 string keys and saved them in plain text files. This is highly insecure because private keys sit unencrypted on the filesystem.
- **`project_previous_2`** used a bash script that called the `keytool` CLI. While this generated secure `.jks` KeyStores, it relied on arbitrary OS terminal commands, making it brittle and platform-dependent (and broke completely if `keytool` wasn't on the system path).

### Our Approach: The [setup_pki.sh]Bash Script
We built a standalone bash script: [setup_pki.sh].

According to the official Oracle Java PKI Programmer's Guide, the `keytool` utility is the standard and recommended way to generate keys and construct Keystores. Instead of trying to use `ProcessBuilder` or recreating raw ASN.1 DER byte streams natively in Java, we completely isolated the heavy lifting into an idiomatic off-line bash script. 

You run this script **once** before starting any nodes:
```bash
chmod +x setup_pki.sh
./setup_pki.sh
```

**Why this is better:**
1. **Separation of Concerns:** Our Java codebase is now 100% focused on *running* the blockchain protocol. It does not contain any bloated startup setup logic.
2. **Oracle Compliant:** By using native `keytool` under the hood, we guarantee our keystores are compliant with all Java security standards.
3. **Cryptographic Isolation:** Every node gets its own isolated, password-protected private keystore (`node-X.p12`). They only share public keys via a consolidated `truststore.p12`.

---

## 2. Core Components

The implementation introduces four independent classes, each with a single responsibility:

### [ConfigReader.java]
A lightweight configuration parser. It reads the [hosts.config] file, ignoring empty lines and `#` comments, and maps each line to a [ProcessInfo] object (`nodeId`, `InetAddress`, `port`).

### [KeyStoreManager.java]
A dedicated wrapper for the `java.security.KeyStore` API. Now that generation is handled externally by Bash, this class only handles the loading aspects at runtime:
- Opening the local `.p12` keystores using a provided password.
- Loading the [PrivateKey] and peer [PublicKey]s directly into memory.

### [setup_pki.sh](file:///home/andresantos/Downloads/depchain-main/new_project/setup_pki.sh) (The Generator)
The offline bash script. Its behavior is straightforward:
1. Reads [hosts.config] line-by-line avoiding comments.
2. Uses `keytool -genkeypair` to generate RSA 2048 keys and self-signed certificates into `.p12` keystores for each node.
3. Uses `keytool -exportcert` and `keytool -importcert` to extract the public keys and consolidate them into a shared `truststore.p12`.

### [NodeConfig.java](file:///home/andresantos/Downloads/depchain-main/new_project/src/main/java/pt/tecnico/depchain/config/NodeConfig.java) (The Runtime Bind)
An immutable Java Record that glues everything together at runtime. It represents the "World State" for a specific node.
When `NodeConfig.load("node-0", ...)` is called, it:
1. Re-parses the [hosts.config].
2. Identifies its own [ProcessInfo]vs its `peers`.
3. Opens `keys/node-0.p12` and decrypts its *own* [PrivateKey].
4. Opens `keys/truststore.p12` and reads all the public keys.
5. Bundles these objects cleanly into memory.

---

## 3. How to Bootstrap the Network

The goal of this new pipeline is to make writing the [DepchainNode.java] [main()] method a trivial one-liner.

Because [NodeConfig] perfectly maps to the constructor arguments required by the [LinkManager], starting the networking stack for a node happens gracefully:

```java
public class DepchainNode {
    public static void main(String[] args) throws Exception {
        String myId = args[0]; // e.g., "node-0"
        
        // 1. Load configuration and pre-distributed keys from disk
        NodeConfig config = NodeConfig.load(
                myId, 
                Path.of("hosts.config"), 
                Path.of("keys"), 
                "sec_project_keys".toCharArray()
        );

        
        // 2. Instantiate application listener
        MessageListener appListener = (senderId, payload) -> {
            System.out.println("App layer received payload from " + senderId);
        };
        
        // 3. One-line LinkManager initialization!
        LinkManager linkManager = new LinkManager(
                config.self(),
                config.peers(),
                config.identityKeyPair(),
                config.peerPublicKeys(),
                appListener
        );
        
        // 4. Start the network stack
        linkManager.start();
    }
}
```

This ensures the [LinkManager] and [AuthenticatedPerfectLink] remain completely blind to the file system, file formats, and CLI passwords. They simply accept the raw `java.security.Key` objects, leaving the architecture modular and easy to test.

---

## 4. Understanding the `keytool` Commands

The [setup_pki.sh] script relies heavily on three core `keytool` commands. Here is what each flag does:

### Step A: Generating the Keys
```bash
keytool -genkeypair -alias "$NODE_ID" \
    -keyalg RSA -keysize 2048 -validity 365 \
    -keystore "$KEYSTORE" \
    -storepass "$PASSWORD" \
    -dname "CN=$NODE_ID" \
    -storetype PKCS12
```
- `-genkeypair`: Tells keytool to generate both a Public Key and a Private Key.
- `-alias "$NODE_ID"`: The internal "name" given to this keypair inside the keystore (e.g., `node-0`).
- `-keyalg RSA -keysize 2048`: Specifies the cryptographic algorithm (RSA) and its strength (2048 bits).
- `-validity 365`: Wraps the public key in a self-signed X.509 certificate that expires in 1 year. (Java KeyStores require private keys to be paired with a certificate).
- `-keystore "$KEYSTORE"`: The path to the `.p12` file where this keypair should be saved.
- `-storepass "$PASSWORD"`: The password used to encrypt and lock the keystore file.
- `-dname "..."`: The "Distinguished Name" for the certificate. It acts as the "Owner Info". `CN` stands for Common Name (we use the Node ID), `OU` is Organizational Unit, etc.
- `-storetype PKCS12`: Forces the modern `.p12` keystore format instead of the legacy proprietary `.jks` format.

### Step B: Exporting the Public Certificate
```bash
keytool -exportcert -alias "$NODE_ID" \
    -keystore "$KEYSTORE" \
    -storepass "$PASSWORD" \
    -file "$CERT_FILE"
```
- `-exportcert`: Commands keytool to extract only the public certificate (leaving the private key safely locked inside).
- `-alias` and `-keystore`: Tell keytool exactly which certificate to extract from which file.
- `-file "$CERT_FILE"`: The temporary `.cer` file to save the exported public key into.

### Step C: Importing into the Truststore
```bash
keytool -importcert -alias "$NODE_ID" \
    -keystore "$TRUSTSTORE" \
    -storepass "$PASSWORD" \
    -file "$CERT_FILE" \
    -noprompt \
    -storetype PKCS12
```
- `-importcert`: Commands keytool to read a `.cer` file and inject it into a new or existing keystore.
- `-keystore "$TRUSTSTORE"`: The path to the shared `truststore.p12` file holding all network public keys.
- `-noprompt`: Tells keytool not to pause execution and ask the user "Do you trust this certificate? [no]:". It automatically forces a "yes".
