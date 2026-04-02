#!/bin/bash
# Script to compile ISTCoin.sol to obtain runtime bytecode (.bytecode)

SOLC_FLAGS="--bin-runtime --optimize -o server/src/main/solidity/bin --base-path server/src/main/solidity/contracts --include-path server/src/main/solidity/contracts/ERC20 server/src/main/solidity/contracts/ISTCoin.sol --overwrite"

if command -v solc &> /dev/null; then
    echo "Compiling using globally installed solc..."
    solc $SOLC_FLAGS
elif [ -x "./solc" ]; then
    echo "Compiling using local solc-static-linux binary..."
    ./solc $SOLC_FLAGS
else
    echo "solc not found. Downloading solc-static-linux (0.8.20)..."
    wget https://github.com/ethereum/solidity/releases/download/v0.8.20/solc-static-linux -O solc
    chmod +x solc
    echo "Compiling using downloaded solc-static-linux binary..."
    ./solc $SOLC_FLAGS
fi

if [ -d "server/src/main/solidity/bin" ]; then
    echo "Renaming .bin-runtime files to .bytecode..."
    for f in server/src/main/solidity/bin/*.bin-runtime; do
        mv "$f" "${f%.bin-runtime}.bytecode" 2>/dev/null
    done
fi

echo "Done! The runtime bytecode should be located at server/src/main/solidity/bin/ISTCoin.bytecode"
