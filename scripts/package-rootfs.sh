#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOTFS_DIR="$PROJECT_DIR/rootfs"
ROOTFS_VERSION="${ROOTFS_VERSION:-$(cat "$ROOTFS_DIR/opt/omniroute-build/version" 2>/dev/null || echo unknown)}"
OUTPUT_DIR="$PROJECT_DIR/dist"
OUTPUT="$OUTPUT_DIR/omniroute-rootfs-$ROOTFS_VERSION.tar.gz"

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

need tar

for required in entrypoint.sh usr/local/bin/node opt/omniroute/server.js; do
  if [[ ! -f "$ROOTFS_DIR/$required" ]]; then
    echo "Rootfs is incomplete: missing $required" >&2
    exit 1
  fi
done

mkdir -p "$OUTPUT_DIR"
rm -f "$OUTPUT.tmp"

# The in-app importer expects archive entries rooted at usr/, opt/, and the
# root-level entrypoint.sh; a wrapper directory would fail validation.
tar --no-same-owner -czf "$OUTPUT.tmp" -C "$ROOTFS_DIR" .
mv "$OUTPUT.tmp" "$OUTPUT"

echo "$OUTPUT"
