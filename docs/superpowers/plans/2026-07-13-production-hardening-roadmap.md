# Production Hardening Platform Roadmap

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement each phase plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the approved production hardening platform in five independently testable phases while keeping the current whole-DEX hardening path available only as an experimental mode.

**Architecture:** Build the release verification core first, then add the Android Gradle plugin/runtime, string encryption, desktop release workflow, and finally the complete device certification gate. Each phase must leave the repository buildable and produce a demonstrable artifact before the next phase begins.

**Tech Stack:** Kotlin 2.1, Gradle Kotlin DSL, Compose Desktop, apksig, ARSCLib, AGP public Variant/Instrumentation APIs, ASM, JUnit 5, Gradle TestKit, adb/emulators.

---

## Phase 1: Release Core Foundation — COMPLETED (2026-07-13)

Detailed plan: `docs/superpowers/plans/2026-07-13-release-core-foundation.md`

Produces:

- `harden-release-core` JVM module;
- APK identity and signer inspection;
- old APK/candidate/keystore comparison;
- strict package, certificate, versionCode, debuggable, testOnly, minSdk and split gates;
- ABI inventory;
- 16KB ZIP and ELF inspection;
- signing without APK content repackaging;
- JSON static verification report;
- headless release-check CLI.

Definition of done:

- release-core unit tests pass from a clean checkout;
- a valid old/candidate/keystore fixture reaches `STATIC_VERIFIED`;
- every configured blocker produces a stable finding code;
- a 16KB-aligned native APK passes and a 4KB-only native APK fails;
- signing changes only signing-related APK regions and verifies with V1/V2/V3.

## Phase 2: Gradle Plugin and Runtime Bootstrap

Status (2026-07-14): implementation and local automation are complete, but the
phase is not marked complete because real-device certification was deferred.
The API 29 device has partial smoke coverage; API 26, API 36/16KB, wrong-signer,
and the final automated debugger-termination run remain mandatory.

Phase-boundary plan target: `docs/superpowers/plans/2026-07-13-gradle-plugin-runtime-bootstrap.md` (write after Phase 1 APIs and tests are stable).

Produces:

- `harden-gradle-plugin`;
- `harden-runtime`;
- discovery of all application variants;
- support for R8 enabled and disabled builds;
- custom/default Application initialization injection;
- signature verification and fixed anti-debug checks;
- per-variant metadata;
- no hidden API references;
- Gradle TestKit fixtures for `product_32`, `product_64`, and `product_all`.

Definition of done:

- all fixture variants build with R8 on and off;
- real Application identity and AppComponentFactory remain unchanged;
- runtime initializes once in every tested process;
- wrong-certificate and debugger tests terminate the process;
- hidden API bytecode scan is empty.

## Phase 3: Build-Time String Protection

Phase-boundary plan target: `docs/superpowers/plans/2026-07-13-string-protection.md` (write after plugin/runtime bootstrap passes).

Produces:

- method-body string LDC transformation;
- safe constant-field transformation;
- AES-GCM per-build encrypted string tables;
- certificate-bound runtime key derivation;
- include/exclude rules and automatic framework-string exclusions;
- bytecode verification and plaintext scans;
- Java, Kotlin, coroutine, Compose, reflection, serialization, Room, Retrofit and JNI fixtures.

Definition of done:

- protected fixture strings disappear from class/DEX scans;
- excluded framework strings remain intact;
- all functional fixture tests pass with R8 on and off;
- modified ciphertext fails authentication;
- size, startup and memory thresholds are measured and reported.

## Phase 4: Desktop Production Release Workflow

Phase-boundary plan target: `docs/superpowers/plans/2026-07-13-desktop-production-release.md` (write after release-core and plugin APIs are stable).

Produces:

- production-release tool in the Compose Desktop application;
- old APK, candidate APK, metadata and keystore selection;
- finding cards and strict blocker UI;
- signing and post-sign verification;
- HTML/JSON reports, certificate PEM and checksums;
- explicit separation from the legacy experimental hardening tool.

Definition of done:

- UI cannot export a formal APK unless status is `STATIC_VERIFIED` or higher;
- every release-core finding is displayed without losing code or severity;
- passwords are not persisted or logged;
- reports are reproducible from the assessment model;
- current scanner and experimental hardening tools still work.

## Phase 5: Device Verification and Release Qualification

Phase-boundary plan target: `docs/superpowers/plans/2026-07-15-device-release-qualification.md`.

Produces:

- `harden-device-tests` runner;
- clean install, update install, launch, process, crash/ANR, wrong signer and debugger tests;
- API 23-36 matrix definitions;
- API 36 4KB and 16KB profiles;
- 32-bit, 64-bit and all-ABI fixtures;
- signed device result ingestion;
- `DEVICE_VERIFIED` and `RELEASE_QUALIFIED` gates.

Definition of done:

- the minimum release matrix is automated;
- old-to-new update succeeds and test data is preserved;
- wrong signer and debugger cases fail as designed;
- no hidden API, writable DEX, crash, ANR, VerifyError or ClassNotFoundException appears;
- release export requires all mandatory device results.

## Sequencing Rules

1. Do not begin a phase until the previous phase tests and verification commands pass.
2. Do not move current classes into a new module and change their behavior in the same commit.
3. Keep commits small: one model, parser, evaluator, UI slice or test harness concern per commit.
4. Use TDD for every behavior change.
5. Keep the existing whole-DEX tool functional until Phase 4 explicitly labels it experimental.
6. Never claim support for an AGP/API/device combination before its automated fixture passes.
7. Re-read `docs/superpowers/specs/2026-07-13-production-hardening-platform-design.md` at every phase boundary.
