# Harden Device Smoke Tests

This module drives an explicitly selected Android device through `adb`. It never
selects an emulator or another connected device implicitly; every command
requires `--serial`.

## Modes

- `profile`: prints API level, ABI list, and page size.
- `survives`: installs an APK, launches it, optionally calls main/worker probe
  providers, and requires the expected processes to remain alive without ANR.
- `preserves`: clean-installs an old APK, writes a private-data marker, updates
  with `adb install -r`, and requires the marker to remain after relaunch.
- `terminates`: installs an APK, launches it, and requires the process to exit
  without ANR or a restart loop.

When `terminates` is used with `--debug true`, the runner starts the Activity
with `am start -D`, forwards the suspended process JDWP endpoint to a random
local port, performs the JDWP handshake, resumes the VM, and then checks that
the runtime terminates the process.

## Examples

```powershell
.\gradlew.bat :harden-device-tests:run --args="--adb C:/AndroidSdk/platform-tools/adb.exe --serial DEVICE_SERIAL --mode profile"
```

```powershell
.\gradlew.bat :harden-device-tests:run --args="--adb C:/AndroidSdk/platform-tools/adb.exe --serial DEVICE_SERIAL --mode survives --apk C:/path/app-release.apk --package com.example.app --activity .MainActivity --main-probe-uri content://com.example.app.main-probe --worker-probe-uri content://com.example.app.worker-probe"
```

```powershell
.\gradlew.bat :harden-device-tests:run --args="--adb C:/AndroidSdk/platform-tools/adb.exe --serial DEVICE_SERIAL --mode terminates --debug true --apk C:/path/app-debug.apk --package com.example.app --activity .MainActivity"
```

```powershell
.\gradlew.bat :harden-device-tests:run --args="--adb C:/AndroidSdk/platform-tools/adb.exe --serial DEVICE_SERIAL --mode preserves --old-apk C:/path/app-old.apk --new-apk C:/path/app-new.apk --package com.example.app --activity .MainActivity --data-probe-uri content://com.example.app.main-probe"
```

For the wrong-signer case, build the fixture with certificate A embedded in
`HardenVariantConfig`, re-sign the final APK with certificate B, verify the APK
contains certificate B, and run `terminates` without `--debug`.

## Release Gate

Do not mark Phase 2 complete until the required real-device matrix has passed.
The remaining minimum coverage is API 26 and API 36 with a 16KB page-size
profile, including custom/default Application, multi-process, wrong-signer, and
debugger termination scenarios.
