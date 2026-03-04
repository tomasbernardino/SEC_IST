#!/bin/bash

# setup_pki.sh
# Reads hosts.config and generates Java Keystores for all defined nodes.
# 
# Usage: ./setup_pki.sh 
# The default password is "sec_project_keys"   

set -e

CONFIG_FILE="hosts.config"
KEYS_DIR="keys"
PASSWORD="sec_project_keys"
TRUSTSTORE="$KEYS_DIR/truststore.p12"

# 1. Verification and Cleanup
if [ ! -f "$CONFIG_FILE" ]; then
    echo "Error: $CONFIG_FILE not found."
    exit 1
fi

echo "Cleaning existing keys directory..."
rm -rf "$KEYS_DIR"
mkdir -p "$KEYS_DIR"

# 2. Key Generation Loop
echo "Starting PKI generation..."

while read -r line || [[ -n "$line" ]]; do
    # Skip empty lines and comments
    if [[ -z "$line" || "$line" == \#* ]]; then
        continue
    fi

    # Read node ID (first column)
    NODE_ID=$(echo "$line" | awk '{print $1}')
    KEYSTORE="$KEYS_DIR/$NODE_ID.p12"
    CERT_FILE="$KEYS_DIR/$NODE_ID.cer"

    echo "======================================"
    echo "Generating keys for: $NODE_ID"

    # Step A: Generate RSA 2048 KeyPair inside the node's individual keystore
    keytool -genkeypair -alias "$NODE_ID" \
        -keyalg RSA -keysize 2048 -validity 365 \
        -keystore "$KEYSTORE" \
        -storepass "$PASSWORD" \
        -dname "CN=$NODE_ID" \
        -storetype PKCS12

    # Step B: Export the public certificate
    keytool -exportcert -alias "$NODE_ID" \
        -keystore "$KEYSTORE" \
        -storepass "$PASSWORD" \
        -file "$CERT_FILE"

    # Step C: Import the public certificate into the shared truststore
    keytool -importcert -alias "$NODE_ID" \
        -keystore "$TRUSTSTORE" \
        -storepass "$PASSWORD" \
        -file "$CERT_FILE" \
        -noprompt \
        -storetype PKCS12

    # Clean up the temporary certificate file
    rm "$CERT_FILE"

done < "$CONFIG_FILE"

echo "======================================"
echo "✓ PKI setup complete!"
echo "Generated private keystores in: $KEYS_DIR/"
echo "Generated shared truststore: $TRUSTSTORE"
