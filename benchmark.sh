#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [ ! -d "bin" ] || [ -z "$(ls -A bin 2>/dev/null)" ]; then
    ./build.sh
fi

echo "Starting LSM-Tree Multi-threaded Virtual Threads Benchmark..."
java --enable-preview -cp bin dev.lsm.cli.LsmBenchmark "$@"
