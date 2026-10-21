#!/usr/bin/env bash
set -euo pipefail

OUTPUT="${1:-app/src/main/jniLibs/arm64-v8a/libproot.so}"
PROOT_SOURCE="${PROOT_SOURCE:-https://github.com/proot-me/proot.git}"
PROOT_REF="${PROOT_REF:-v5.4.0}"
ALPINE_IMAGE="${ALPINE_IMAGE:-alpine:3.20}"

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

need docker
need cp

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

docker run --rm --platform linux/arm64/v8 \
  -e PROOT_SOURCE="$PROOT_SOURCE" \
  -e PROOT_REF="$PROOT_REF" \
  -v "$tmpdir:/work" -w /work "$ALPINE_IMAGE" sh -e -c '
    apk add --no-cache build-base git linux-headers make
    git clone --depth 1 --branch "$PROOT_REF" "$PROOT_SOURCE" proot
    cd proot/src
    make clean
    make LDFLAGS="-static" CFLAGS="-O2 -static"
    cp proot /work/libproot.so
  '

mkdir -p "$(dirname "$OUTPUT")"
cp "$tmpdir/libproot.so" "$OUTPUT"
echo "Static arm64 proot written to: $OUTPUT"
