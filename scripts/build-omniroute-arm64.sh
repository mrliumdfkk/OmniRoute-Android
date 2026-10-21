#!/usr/bin/env bash
set -euo pipefail

# Builds OmniRoute's standalone Next.js server (dist/ with colocated
# node_modules) for linux/arm64, which is what the Android rootfs needs.
#
# A generic `npm pack omniroute` tarball is NOT usable here: npm packages do not
# ship node_modules, and native modules such as better-sqlite3 must be compiled
# for arm64.

OUT="${1:-omniroute-arm64.tar.gz}"
REPO="${OMNIROUTE_REPO:-https://github.com/diegosouzapw/OmniRoute.git}"
REF="${OMNIROUTE_REF:-main}"
IMAGE="${BUILD_IMAGE:-node:22-bookworm}"

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

need docker

OUT_DIR="$(cd "$(dirname "$OUT")" && pwd)"
OUT_NAME="$(basename "$OUT")"

echo "Building OmniRoute ($REF) for linux/arm64 with $IMAGE"
echo "This needs several GB of RAM and disk; Next.js builds peak around 6 GB."

docker run --rm --platform linux/arm64/v8 \
  -e REPO="$REPO" \
  -e REF="$REF" \
  -e OUT_NAME="$OUT_NAME" \
  -v "$OUT_DIR:/out" \
  -w /work \
  "$IMAGE" \
  bash -euo pipefail -c '
    apt-get update
    apt-get install -y --no-install-recommends \
      ca-certificates git python3 make g++ >/dev/null

    git clone --depth 1 --branch "$REF" "$REPO" /work/src
    cd /work/src

    npm ci --legacy-peer-deps --include=optional
    npm run build

    test -f dist/server.js
    test -d dist/node_modules

    tar --no-same-owner -czf "/out/$OUT_NAME" -C dist .
  '

echo "OmniRoute arm64 standalone written to: $OUT_DIR/$OUT_NAME"
