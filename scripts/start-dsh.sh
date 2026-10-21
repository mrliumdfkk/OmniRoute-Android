#!/bin/sh
# start-dsh.sh — Launch dsh (DeepSeek Harness) inside the omniroute-android
# rootfs, bridged to the local OmniRoute gateway.
#
# This script is the rootfs-side counterpart of the dsh→OmniRoute bridge.
# It is called from entrypoint.sh AFTER OmniRoute is up on $OMNIROUTE_PORT,
# or manually for testing:
#
#   start-dsh.sh
#
# Environment it reads:
#   OMNIROUTE_PORT  OmniRoute's listen port (default 20128, set by
#                  entrypoint.sh from AppSettings). dsh will POST its model
#                  calls to http://127.0.0.1:$OMNIROUTE_PORT/v1/messages.
#   DSH_PORT       dsh web UI listen port (default 3080).
#   DSH_HOME       dsh data dir (default /data/.dsh); stores profiles/sessions.
#
# Why env-var injection instead of hard-coding baseURL in the patch:
#   dsh's resolveAdapterOptions() resolves baseURL as
#     config.baseURL  ??  $DEEPSEEK_BASE_URL  ??  PUBLIC_BASE_URL
#   The patch (assets/dsh/omniroute.patch.yml) deliberately sets NO baseURL,
#   so this script injects $DEEPSEEK_BASE_URL and dsh falls back to it. That
#   way a user who changes OmniRoute's port in the Android settings page only
#   has to restart — the patch file never needs editing.
set -eu

# --- OmniRoute target -------------------------------------------------------
OMNIROUTE_PORT="${OMNIROUTE_PORT:-20128}"
DSH_PORT="${DSH_PORT:-3080}"
DSH_HOME="${DSH_HOME:-/data/.dsh}"
export DSH_HOME
mkdir -p "$DSH_HOME"

# Tell dsh where OmniRoute is. OmniRoute's /v1/messages endpoint is keyless for
# the `auto` model, so a placeholder API key is enough to satisfy dsh's own
# assertUsableApiKey() check (it only verifies non-empty + HTTP-header-safe).
export DEEPSEEK_BASE_URL="http://127.0.0.1:${OMNIROUTE_PORT}"
export DEEPSEEK_API_KEY="omniroute-no-key"

# --- locate the dsh payload -------------------------------------------------
# The build step (scripts/build-dsh-arm64.sh, stage 1) extracts the dsh monorepo
# to /opt/dsh with node_modules intact. Until stage 1 lands, this script also
# accepts a checkout at /opt/dsh laid out by hand for testing.
DSH_DIR="/opt/dsh"
BIN="${DSH_DIR}/apps/cli/src/bin.ts"

if [ ! -f "$BIN" ]; then
  echo "start-dsh: dsh payload not found at ${BIN}." >&2
  echo "  Stage 1 (build-dsh-arm64.sh) has not run yet; see omniroute.patch.yml." >&2
  exit 1
fi

# The patch is shipped as an Android asset; inside the rootfs it is copied to
# /data/assets/dsh/ by RootfsInstaller (stage 1). Fallback to the source path
# for manual testing.
PATCH="/data/assets/dsh/omniroute.patch.yml"
[ -f "$PATCH" ] || PATCH="${DSH_DIR}/app/src/main/assets/dsh/omniroute.patch.yml"

# dsh web: a long-running HTTP server on $DSH_PORT serving the browser UI.
# omniroute-android's WebView loads http://127.0.0.1:$DSH_PORT instead of the
# OmniRoute dashboard.
#
# tsx is required because dsh's source is .ts; the built lib/ is the stage-1
# target. For now we run from source via tsx (already in node_modules).
exec /usr/bin/tini -g -- \
  /usr/bin/node --import tsx/esm "$BIN" \
  --profile web \
  --port "$DSH_PORT" \
  --no-open \
  --patch "$PATCH"
