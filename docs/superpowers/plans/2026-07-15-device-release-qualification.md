# Device Release Qualification Plan

## Completed Offline Work

- [x] Define API 26, API 36/4KB, API 36/16KB, wrong-signer, and debugger scenarios.
- [x] Add integrity-protected device result JSON.
- [x] Require every scenario exactly once.
- [x] Bind imported results to the final signed APK SHA-256.
- [x] Add `STATIC_VERIFIED` bundle to `RELEASE_QUALIFIED` promotion flow.
- [x] Block crash, ANR, forbidden-log, package, version, and digest mismatches.

## Remaining Physical Verification

- [ ] Run the selected API 26 real device.
- [ ] Run API 36 4KB and 16KB real-device profiles.
- [ ] Run wrong-signer termination.
- [ ] Run debugger termination with `am start -D`.
- [ ] Run clean install and update install with data-preservation probes.
- [ ] Import signed results and confirm the final release bundle.

No device or release qualification claim is valid until all physical scenarios
are passed and imported against the exact final signed APK.
