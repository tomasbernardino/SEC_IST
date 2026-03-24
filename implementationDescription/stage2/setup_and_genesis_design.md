# Initial Setup & Genesis Generation

This document details the architecture and mechanisms behind DepChain's static system initialization in Stage 2.

## 1. The Goal: Hybrid Cryptography

To properly integrate the EVM and Ethereum transaction standards (which fundamentally rely on `ecrecover` and `secp256k1` ECDSA keys) while maintaining the battle-tested network layer from Stage 1 (which relies on `RSA` and Java Keystores), DepChain adopted a **Hybrid Cryptography Model**:

1. **Network Identity (RSA):** Used strictly by the `LinkManager` and `AuthenticatedPerfectLink` to authorize physical TCP/UDP connections between nodes and clients.
2. **Blockchain Identity (ECDSA):** Used strictly to sign and verify state-mutating Transactions within the Ethereum Virtual Machine (EVM).

This required a complete overhaul of the initial setup generation process to output both types of artifacts simultaneously.

## 2. Unified Implementation: `SystemSetupTool`

Instead of relying on fragmented bash scripts (`setup_pki.sh`, `setup_client.sh`), the entire initialization pipeline has been consolidated into a single Java application: `SystemSetupTool.java`. 

This tool is executed automatically via the new `setup_system.sh` pipeline, dramatically simplifying cluster creation.

### 2.1 Artifact Generation Pipeline
When run via `./setup_system.sh 4 2` (4 nodes, 2 clients), the tool performs a rigid 5-step pipeline:

1. **Node RSA Keys:** Sub-processes Java `keytool` to generate `node-X.p12` keystores and load them into a shared `truststore.p12`.
2. **Client RSA Keys:** Repeats the process for clients, generating `client-X.p12`. This allows the `LinkManager` to accept client connections dynamically.
3. **Client ECDSA Keys:** Uses the `web3j` library to generate standard `secp256k1` `ECKeyPairs` for the clients. It exports the private keys to `client-X.key` and calculates their 40-character Ethereum address (e.g., `0x66e6...`).
4. **Address Mapping:** Dumps the client ID-to-Address mapping into `addresses.config`, vital for the Client CLI application to route user commands easily.
5. **Genesis Block Generation:** Generates the `genesis.json` world state, creating EOA accounts for the clients and mapping the initial deployment of the smart contracts.
The instant the nodes boot, the `ISTCoin` contract already exists at a pre-deterministic address, fully funded and fully initialized, effectively eliminating complex startup races and fragile deployment scripts.

All artifacts are placed into a centralized `setup_config/` folder at the root of the project, completely isolating the generated cluster state from the repository's source code.


