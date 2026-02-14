#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

mkdir -p bin

echo "Compiling Java 21 sources with javac (Pure Java, Zero Dependencies)..."
SOURCES=$(find src/main/java src/test/java -name "*.java")

javac -d bin --enable-preview --source 21 $SOURCES

echo "Build successful! Bytecode output saved to bin/"
