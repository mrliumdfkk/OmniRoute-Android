# OmniRoute Android (Debian proot packaging)

This is a minimal packaging skeleton that bundles OmniRoute and a Debian `arm64`
rootfs in an Android APK. The Android shell launches the rootfs with `proot`,
waits for OmniRoute to bind `127.0.0.1:20128`, and then opens the existing
OmniRoute dashboard in a `WebView`.

## Prerequisites

- Android SDK 36 and Android NDK installed
- Gradle 8.14+ on `PATH` (the build scripts call `gradle` directly); run
  `gradle wrapper --gradle-version 8.14.3` if you prefer the checked-in
  `gradle/wrapper/gradle-wrapper.properties` to drive a wrapper
- `sudo`, `debootstrap`, `qemu-aarch64-static`, `tar`, and `xz-utils`
- An `arm64-v8a` build of `proot` named `libproot.so`
- An OmniRoute `linux/arm64` standalone build (`dist/server.js` plus
  `dist/node_modules`), produced by `scripts/build-omniroute-arm64.sh`

### Build the OmniRoute arm64 payload

```bash
./scripts/build-omniroute-arm64.sh omniroute-arm64.tar.gz
```

This runs `npm ci && npm run build` inside an `arm64` Node image, because
`better-sqlite3` and the other native modules must be compiled for `arm64`.
A `npm pack omniroute` tarball is not a substitute; npm packages ship no
`node_modules`.

### Optional: build a static arm64 `proot`

On a machine with Docker and binfmt/QEMU enabled for `linux/arm64`, run:

```bash
./scripts/build-proot.sh app/src/main/jniLibs/arm64-v8a/libproot.so
```

This produces a statically linked `proot` binary that can be executed directly
from the APK's `nativeLibraryDir` without requiring Termux or a glibc loader.

## Build steps

1. Prepare the Debian `arm64` rootfs. This must run on an `arm64` host, or on a
   host with `qemu-user-static`/binfmt registered so the `chroot` can execute
   the `arm64` Node.js installer:

   ```bash
   sudo apt-get install qemu-user-static binfmt-support
   ./scripts/build-rootfs.sh ./rootfs ./omniroute-arm64.tar.gz
   ```

   The second argument is optional; omit it to produce a rootfs without
   OmniRoute and drop it in at `/opt/omniroute` inside the archive later.

2. Provide the `proot` binary as `app/src/main/jniLibs/arm64-v8a/libproot.so`,
   either from `./scripts/build-proot.sh` or a Termux build.

3. Package the full rootfs for local import:

   ```bash
   ./scripts/package-rootfs.sh
   ```

   The output is `dist/omniroute-rootfs-<version>.tar.gz`. Transfer it to the
   device once, then import it from **Settings → Runtime Import**.

4. Build the small APK:

   ```bash
   ./scripts/package-apk.sh
   ```

The resulting APKs are written to `app/build/outputs/apk/`.

## Runtime layout and updates

The Android shell keeps the rootfs and the user data in separate locations, so
importing a new full package upgrades the runtime without touching user data:

| Path | Content | Survives APK update |
| --- | --- | --- |
| `files/rootfs/<version>/` | Debian rootfs + Node + OmniRoute | replaced |
| `files/installed-rootfs.version` | Marker naming the active rootfs | rewritten |
| `files/omniroute-data/` | SQLite DB, `.env`, caches — mounted at `/data` | kept |
| `files/logs/omniroute.log` | Captured server stdout/stderr | kept |
| `external/files/export/` | Manual data backups (see below) | kept |

On launch the app uses the already-imported rootfs. `ImportActivity` opens the
full package through SAF, copies it into app-private storage, extracts into
`files/rootfs-import-staging/`, verifies the runtime, atomically renames the
staging directory to `files/rootfs/<version>/`, writes the marker, and only
then prunes the old rootfs. A failed extraction never replaces the working
rootfs. Restarting the service runs the imported tree.

The rootfs version defaults to the build date plus Node major, override it with
`ROOTFS_VERSION=...` when running `scripts/build-rootfs.sh`.

The app does not shell out to the device `tar`: `TarExtractor` decompresses the
gzip stream and unpacks ustar/GNU-long-name/pax entries in-process, applies the
recorded permission bits, and refuses entries whose resolved path escapes the
rootfs. `entrypoint.sh` still `chmod +x`es `node` and `tini` defensively, and
the app invokes it as `/bin/sh /entrypoint.sh` rather than relying on the
shebang, because a non-root extraction cannot restore root ownership.

## Data backups

`Export data backup` in the app zips `omniroute-data/` into
`Android/data/<package>/files/export/`, which is reachable over USB/SAF without
root. `DataStore.importData()` restores that archive and is intentionally not
wired to a button yet — call it after stopping the service.

## Keeping the server alive

- `OmniRouteService` is a `specialUse` foreground service with a persistent
  notification, `START_STICKY`, and a 15-second supervisor that restarts the
  proot process if it dies.
- `BootReceiver` starts the service on `BOOT_COMPLETED`.
- The app requests `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; Android still kills
  background work on aggressive OEM ROMs, so pair it with a foreground service
  and a user-visible notification.

## Limitations

- The APK stays small, but importing a full package still requires enough
  device storage for the compressed archive plus the extracted rootfs during
  the update.
- `onnxruntime-node`, `wreq-js`, and Playwright-backed providers are not
  guaranteed to work on Android and should be omitted from the runtime tree if
  not required.
- OmniRoute recommends 8–12 GiB of heap for long coding-agent requests. Android
  builds are best suited for dashboard and light-chat workloads unless the
  device has substantial memory.
- Android still terminates background work on aggressive OEM ROMs even with a
  foreground service, so the supervisor can only restart the gateway once the
  app is allowed to run again.
- Rootfs archives larger than ~1 GiB cannot be read from APK assets; split them
  with Play Asset Delivery in production.
- The rootfs asset is shipped uncompressed inside the APK and is excluded from
  Android auto-backup; `minifyEnabled` stays off because the wrapper is a thin
  shell that R8 gains little from.


## Building the APK

The release build is signed with a key that is **not** part of this repository.
`release.keystore` and `signing.properties` are git-ignored; supply your own:

```bash
keytool -genkeypair -v -keystore release.keystore -alias omniroute \
        -keyalg RSA -keysize 2048 -validity 10000
```

Then describe the key in `signing.properties`:

```properties
storeFile=release.keystore
storePassword=...
keyAlias=omniroute
keyPassword=...
```

`local.properties` must point at your SDK, and is also git-ignored:

```properties
sdk.dir=/path/to/android-sdk
```

With those in place:

```bash
gradle assembleRelease --no-daemon
```

The output is `app/build/outputs/apk/release/app-release.apk`. Without a
`signing.properties` file the release variant falls back to the debug
keystore, which is fine for local testing but not for distribution.

## OAuth authorization flows

The provider list in the OAuth screen is generated from a single `PRESETS`
array in `OAuthConfig.java`, so adding a provider is a one-line change.

The transport used for a provider is **not** decided on the Android side. The
runtime reports a `flowType` when `/api/oauth/{provider}/authorize` is called,
and the app dispatches on that value:

| `flowType` | What the app does |
| --- | --- |
| `device_code` | Shows the user code and verification URI, then polls `/api/oauth/{provider}/poll` |
| `authorization_code_pkce` | Opens the authorization URL and waits for the redirect |
| `authorization_code` | Opens the authorization URL and waits for the redirect |
| `callback_server` | Lets the runtime host the callback and asks it for the result |
| `import` | Shows the token import form |

Providers that report "not configured", "experimental feature disabled", or
"import only" surface as an explicit error instead of silently falling back to
a different flow. The preset entry is a hint for the user, not the source of
truth — the runtime's `flowType` always wins.

Authorization codes, PKCE verifiers, and client credentials stay inside the
rootfs gateway. The Android app only starts the flow, receives the callback,
and shows status.
