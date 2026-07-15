# Desktop Production Release Workflow Plan

**Goal:** Add a production release workflow to the Compose Desktop application
that statically verifies an update, signs it without rewriting APK content, and
exports a complete auditable release bundle.

**Architecture:** `harden-release-core` owns deterministic release artifacts and
the gated analyze/sign/re-verify workflow. The existing desktop application is
only an input/state/rendering layer. The legacy whole-DEX tool remains available
but is explicitly labeled experimental and cannot produce a production status.

## Task 1: Add deterministic release artifacts

- [x] Render every assessment field and finding to deterministic HTML.
- [x] Export the signer leaf certificate as PEM.
- [x] Generate sorted SHA-256 checksums for every release artifact.
- [x] Escape all untrusted report content and never include passwords or keys.

## Task 2: Add the statically gated release workflow

- [x] Analyze online APK, candidate APK, metadata, and keystore before signing.
- [x] Refuse export unless the pre-sign status is `STATIC_VERIFIED`.
- [x] Sign to a staging directory and verify non-signature APK content is unchanged.
- [x] Re-run the complete analyzer against the signed APK.
- [x] Atomically publish APK, JSON, HTML, PEM, and checksums as one release bundle.
- [x] Leave no formal bundle when analysis, signing, or post-sign verification fails.

## Task 3: Add the desktop production-release tool

- [x] Select online APK, candidate APK, metadata, keystore, and output directory.
- [x] Accept alias and masked passwords without persistence or logging.
- [x] Show status plus every finding code, level, message, and detail.
- [x] Support explicit approval for `REQUIRES_APPROVAL` findings.
- [x] Disable export until all inputs are valid and analysis is statically verified.
- [x] Clear password state after every analyze/export attempt.
- [x] State clearly that `STATIC_VERIFIED` is not device or release qualification.

## Task 4: Separate the legacy experimental workflow

- [x] Rename the existing navigation item to make its experimental status obvious.
- [x] Display `实验模式，不允许用于正式发布` in the whole-DEX screen.
- [x] Keep the existing hardening and privacy-scanner behavior working.

## Task 5: Verify Phase 4

- [x] Run release-core tests, desktop tests, and `validatePlugins`.
- [x] Verify a valid fixture exports a complete signed bundle.
- [x] Verify a blocked fixture exports nothing.
- [x] Verify JSON and HTML are derived from the same assessment.
- [x] Verify no password appears in logs, reports, or committed fixtures.

Phase 4 ends at `STATIC_VERIFIED`. Device verification remains deferred and no
artifact may be described as `RELEASE_QUALIFIED` until Phase 5 passes.
