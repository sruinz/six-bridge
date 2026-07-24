# SIX Bridge

SIX Bridge is an experimental Kotlin/JVM tool that packages Chrome/Edge
Manifest V3 extensions for Samsung Internet 30.x's observed `dev_ext` path.
It does not modify Samsung Internet, install silently, use root/Shizuku, or
publish third-party extensions.

The current implementation is the command-line PoC and the signed Android
wrapper. The Android manager is deliberately gated on successful Fold7
recognition of a self-signed wrapper.

## Requirements

- JDK 17 or newer
- Gradle 9.5 or newer
- Android SDK platform 36 and build-tools 36
- ADB for device commands

Build and install the command:

```sh
./scripts/verify.sh
export PATH="$PWD/cli/build/install/sixbridge/bin:$PATH"
```

Create the baseline browser profile:

```sh
sixbridge profile \
  --browser-apk "com.sec.android.app.sbrowser_30.0.2.61-1300261502_minAPI30(arm64-v8a,armeabi-v7a)(nodpi)_apkmirror.com.apk"
```

Analyze and build the minimal gate:

```sh
sixbridge analyze \
  --input fixtures/minimal-mv3 \
  --profile profiles/sbrowser-30.0.2.61.json

sixbridge build \
  --input fixtures/minimal-mv3 \
  --profile profiles/sbrowser-30.0.2.61.json
```

Install and collect device evidence:

```sh
sixbridge device install \
  --bundle dist/<extension-id>.six \
  --serial <adb-serial>

sixbridge device smoke \
  --bundle dist/<extension-id>.six \
  --serial <adb-serial>
```

For the first Fold7 gate, the host-side checks can also be run in one command:

```sh
./scripts/fold7-gate.sh <adb-serial>
```

Inputs may be a local source directory, ZIP, CRX3, or Chrome Web Store URL.
Edge source/ZIP/CRX3 works through the same path; automatic Edge Store
downloading is intentionally deferred.

Generated source snapshots, keys and evidence are under `.local/` and ignored
by Git. Keep that directory backed up: losing either the CRX key or wrapper
PKCS12 key changes identity and prevents an Android in-place update.

See [architecture](docs/architecture.md) and the
[Fold7 validation checklist](docs/fold7-validation.md). Current host-side
evidence is recorded in [PoC verification results](docs/poc-results.md).
