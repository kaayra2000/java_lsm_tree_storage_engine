#!/usr/bin/env bash
set -euo pipefail

mkdir -p bin
echo "Building project..."
javac -d bin $(find src -name "*.java" 2>/dev/null || true)
