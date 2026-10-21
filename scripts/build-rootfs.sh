#!/usr/bin/env bash
set -euo pipefail

ROOTFS="$1"
OMNIROUTE_TARBALL="${2:-}"
DSH_TARBALL="${3:-}"
DEBIAN_MIRROR="${DEBIAN_MIRROR:-http://deb.debian.org/debian}"
NODE_VERSION="${NODE_VERSION:-22}"
ROOTFS_VERSION="${ROOTFS_VERSION:-$(date -u +%Y.%m.%d)-node${NODE_VERSION}}"

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

need sudo
need debootstrap
need tar

if [[ "$(uname -m)" != "aarch64" ]]; then
  need qemu-aarch64-static
fi

rm -rf "$ROOTFS"
mkdir -p "$ROOTFS"
sudo debootstrap --arch=arm64 --variant=minbase bookworm "$ROOTFS" "$DEBIAN_MIRROR"

sudo cp "$(command -v qemu-aarch64-static)" "$ROOTFS/usr/bin/"

SETUP_SCRIPT="$(mktemp)"
trap 'rm -f "$SETUP_SCRIPT"' EXIT
cat > "$SETUP_SCRIPT" <<EOF
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends \
  ca-certificates curl gnupg xz-utils tini

curl -fsSL https://deb.nodesource.com/setup_${NODE_VERSION}.x | bash -
apt-get install -y --no-install-recommends nodejs
rm -f /etc/apt/sources.list.d/nodesource.list
apt-get remove -y gnupg
apt-get autoremove -y
apt-get clean
rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/* /root/.cache/*
EOF
sudo cp "$SETUP_SCRIPT" "$ROOTFS/setup.sh"
sudo chroot "$ROOTFS" /bin/sh -e /setup.sh
sudo rm -f "$ROOTFS/setup.sh"
sudo rm -f "$ROOTFS/usr/bin/qemu-aarch64-static"

mkdir -p "$ROOTFS/opt/omniroute" "$ROOTFS/rootfs"
if [[ -n "$OMNIROUTE_TARBALL" ]]; then
  # `tar -xf` auto-detects gzip/xz so either artifact works here.
  tar --no-same-owner -xf "$OMNIROUTE_TARBALL" -C "$ROOTFS/opt/omniroute"
fi

# Stage 1: optionally unpack the dsh monorepo payload (build-dsh-arm64.sh).
# The tarball layout is the repo root: apps/, packages/, node_modules/, etc.
# It is extracted to /opt/dsh so start-dsh.sh finds apps/cli/src/bin.ts.
if [[ -n "$DSH_TARBALL" ]]; then
  mkdir -p "$ROOTFS/opt/dsh"
  tar --no-same-owner -xf "$DSH_TARBALL" -C "$ROOTFS/opt/dsh"

  # Copy the start script and the patch into the rootfs.
  mkdir -p "$ROOTFS/opt/dsh/scripts" "$ROOTFS/data/assets/dsh"
  cp "$(dirname "$0")/start-dsh.sh" "$ROOTFS/opt/dsh/scripts/start-dsh.sh"
  chmod +x "$ROOTFS/opt/dsh/scripts/start-dsh.sh"
  cp "$(dirname "$0")/../app/src/main/assets/dsh/omniroute.patch.yml" \
     "$ROOTFS/data/assets/dsh/omniroute.patch.yml"
fi

cat > "$ROOTFS/entrypoint.sh" <<'EOF'
#!/bin/sh
set -e

# A non-root extraction may drop the executable bits, so repair the binaries we
# depend on before using them.
chmod +x /usr/bin/node /usr/bin/tini 2>/dev/null || true

export PORT="${PORT:-20128}"
export HOSTNAME="${HOSTNAME:-127.0.0.1}"
export DATA_DIR="/data"
export HOME="${HOME:-/data}"
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
export TMPDIR="/tmp"
export XDG_CACHE_HOME="/data/.cache"
mkdir -p "$XDG_CACHE_HOME" /tmp

# Recreate the /dev nodes dropped from the shipped archive: tar cannot create
# device nodes without root. The host /dev is bind-mounted over this directory,
# so only repair the cases where that mount did not provide the node.
if [ ! -c /dev/null ]; then
  mount -t tmpfs tmpfs /dev 2>/dev/null || true
  mknod -m 666 /dev/null c 1 3 2>/dev/null || true
  mknod -m 666 /dev/zero c 1 5 2>/dev/null || true
  mknod -m 444 /dev/random c 1 8 2>/dev/null || true
  mknod -m 444 /dev/urandom c 1 9 2>/dev/null || true
fi

# Launch OmniRoute in the background, then wait for its port before
# starting dsh (which POSTs model calls to OmniRoute's /v1/messages).
# If dsh is not installed, fall back to the original foreground OmniRoute.
OMNIROUTE_PORT="${PORT:-20128}"

if [ -f /opt/omniroute/dist/server.js ]; then
  OMNIROUTE_BIN="/opt/omniroute/dist/server.js"
else
  OMNIROUTE_BIN="/opt/omniroute/bin/omniroute.mjs"
  # The .mjs entry needs a `serve` argument.
  export OMNIROUTE_BIN_ARGS="serve"
fi

# Start OmniRoute in the background under tini.
if [ -n "${OMNIROUTE_BIN_ARGS:-}" ]; then
  /usr/bin/tini -g -- /usr/bin/node "$OMNIROUTE_BIN" $OMNIROUTE_BIN_ARGS &
else
  /usr/bin/tini -g -- /usr/bin/node "$OMNIROUTE_BIN" &
fi
OMNIROUTE_PID=$!

# Wait for OmniRoute to bind. The dashboard has no health endpoint, so poll
# the TCP port; 30s covers a cold start of the Next.js standalone server.
for i in $(seq 1 60); do
  if /usr/bin/node -e "require('net').createConnection(${OMNIROUTE_PORT},'127.0.0.1').on('connect',()=>process.exit(0)).on('error',()=>process.exit(1))" 2>/dev/null; then
    break
  fi
  sleep 0.5
done

# If dsh is installed, exec it in the foreground (it is the user-facing surface);
# OmniRoute keeps running in the background as PID $OMNIROUTE_PID.
if [ -x /opt/dsh/scripts/start-dsh.sh ]; then
  export OMNIROUTE_PORT
  exec /opt/dsh/scripts/start-dsh.sh
fi

# No dsh: fall back to the original foreground OmniRoute. Wait on the PID so
# the container stays alive as long as OmniRoute runs.
wait "$OMNIROUTE_PID"
EOF
chmod +x "$ROOTFS/entrypoint.sh"

mkdir -p "$ROOTFS/opt/omniroute-build"
printf '%s\n' "$ROOTFS_VERSION" > "$ROOTFS/opt/omniroute-build/version"

# Device nodes cannot be created by a non-root extraction on Android.
rm -rf "$ROOTFS/dev"
mkdir -p "$ROOTFS/dev"
mkdir -p "$ROOTFS/tmp"
chmod 1777 "$ROOTFS/tmp"

echo "Debian arm64 rootfs prepared at: $ROOTFS (version $ROOTFS_VERSION)"
