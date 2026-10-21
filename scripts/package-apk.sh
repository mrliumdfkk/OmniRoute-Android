#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

need gradle

rm -f "$PROJECT_DIR/app/src/main/assets/rootfs/omniroute-rootfs.tar.gz" \
      "$PROJECT_DIR/app/src/main/assets/rootfs/omniroute-rootfs.bundle" \
      "$PROJECT_DIR/app/src/main/assets/rootfs/omniroute-rootfs.version"

cd "$PROJECT_DIR"
gradle :app:assembleDebug
gradle :app:assembleRelease

echo "APKs are available under app/build/outputs/apk/."
echo "Build the full runtime separately with scripts/build-rootfs.sh."
echo "Package it for device import with scripts/package-rootfs.sh."
