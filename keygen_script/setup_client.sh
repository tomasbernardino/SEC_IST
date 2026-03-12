#!/bin/bash

# setup_client.sh
# Generates a single PKCS12 keystore and updates the shared truststore
# for a client identifier passed on the command line.
#
# Usage: ./setup_client.sh client-0
# The default password is "sec_project_keys"

set -e

# determine script directory so paths work regardless of cwd

KEYS_DIR="keys"
PASSWORD="sec_project_keys"
TRUSTSTORE="$KEYS_DIR/truststore.p12"

# cleanup function to remove cert file on exit (successful or not)
cleanup() {
    if [[ -n "$CERT_FILE" && -f "$CERT_FILE" ]]; then
        rm -f "$CERT_FILE" && echo "$CERT_FILE removed after import."
    fi
}
trap cleanup EXIT

if [[ -z "$1" ]]; then
    echo "Usage: $0 <client-id>"
    exit 1
fi

CLIENT_ID="$1"
KEYSTORE="$KEYS_DIR/$CLIENT_ID.p12"
CERT_FILE="$KEYS_DIR/$CLIENT_ID.cer"

# ensure keys directory exists
mkdir -p "$KEYS_DIR"

echo "Generating keys for: $CLIENT_ID"

# create a keypair in the client's keystore
keytool -genkeypair -alias "$CLIENT_ID" \
    -keyalg RSA -keysize 2048 -validity 365 \
    -keystore "$KEYSTORE" \
    -storepass "$PASSWORD" \
    -dname "CN=$CLIENT_ID" \
    -storetype PKCS12

# export the public certificate
keytool -exportcert -alias "$CLIENT_ID" \
    -keystore "$KEYSTORE" \
    -storepass "$PASSWORD" \
    -file "$CERT_FILE"

# import the certificate into the shared truststore (creating it if necessary)
keytool -importcert -alias "$CLIENT_ID" \
    -keystore "$TRUSTSTORE" \
    -storepass "$PASSWORD" \
    -file "$CERT_FILE" \
    -noprompt \
    -storetype PKCS12

# certificate file will be removed by cleanup trap

echo "Client PKI entry complete!"
echo "Keystore: $KEYSTORE"
echo "Updated truststore: $TRUSTSTORE"
