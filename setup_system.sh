#!/bin/bash
# setup_system.sh
# Unified setup script that generates ALL static system artifacts:
#   - RSA node keys (keytool → .p12 keystores)
#   - Client RSA keys (keytool → .p12 for network auth)
#   - ECDSA client keys (web3j → .key files for blockchain identity)
#   - addresses.config (client blockchain addresses)
#   - genesis.json (initial blockchain state)
#
# Usage: ./setup_system.sh [nrNodes] [nrClients]
#   Defaults: 4 nodes, 2 clients

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR"
SERVER_DIR="$PROJECT_DIR/server"
NR_NODES="${1:-4}"
NR_CLIENTS="${2:-2}"
OUTPUT_DIR="${3:-$PROJECT_DIR/setup_config}"

KEYS_DIR="$OUTPUT_DIR/keys"
SOURCE_HOSTS="$PROJECT_DIR/hosts.config"
PASSWORD="sec_project_keys"

echo "╔══════════════════════════════════════╗"
echo "║     DepChain System Setup Tool       ║"
echo "╠══════════════════════════════════════╣"
echo "║  Nodes:   $NR_NODES                            ║"
echo "║  Clients: $NR_CLIENTS                            ║"
echo "║  Output:  $OUTPUT_DIR"
echo "╚══════════════════════════════════════╝"
echo ""

# Check source hosts.config exists
if [ ! -f "$SOURCE_HOSTS" ]; then
    echo "Error: $SOURCE_HOSTS not found."
    echo "Please create hosts.config in the ${PROJECT_DIR} directory first."
    exit 1
fi

# Prepare output directory
mkdir -p "$OUTPUT_DIR"

# Build the project first (skip tests)
echo "Building project..."
cd "$PROJECT_DIR"
mvn -q install -DskipTests 2>/dev/null || {
    echo "Warning: Full build failed, trying to build just common + server..."
    mvn -q install -pl common,server -DskipTests -am
}

# Run the unified setup tool
echo ""
echo "Running SystemSetupTool..."
COMMON_DIR="$PROJECT_DIR/common"
cd "$COMMON_DIR"
mvn -q exec:java \
    -Dexec.mainClass="ist.group29.depchain.common.util.SystemSetupTool" \
    -Dexec.args="$NR_NODES $NR_CLIENTS $KEYS_DIR $SOURCE_HOSTS $OUTPUT_DIR $PASSWORD"

echo ""
echo "╔══════════════════════════════════════╗"
echo "║         Setup Complete!              ║"
echo "╠══════════════════════════════════════╣"
echo "║  All files saved to: $OUTPUT_DIR"
echo "║  - Keys:        $KEYS_DIR"
echo "║  - Addresses:   $OUTPUT_DIR/addresses.config"
echo "║  - Genesis:     $OUTPUT_DIR/genesis.json"
echo "╚══════════════════════════════════════╝"
