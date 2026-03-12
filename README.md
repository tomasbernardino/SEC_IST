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

