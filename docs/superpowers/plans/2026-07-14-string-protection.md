# Build-Time String Protection Implementation Plan

**Goal:** Protect selected business strings at build time without hidden APIs,
runtime file extraction, eager plaintext loading, or changes to framework-owned
string contracts.

**Architecture:** A small pure-JVM crypto module owns the shared key derivation
and AES-GCM contract. The Gradle plugin collects eligible strings, generates an
encrypted per-variant table, and instruments project classes through AGP ASM.
The Android runtime initializes the generated table after signer verification
and decrypts entries lazily into an `AtomicReferenceArray<String>` cache.

## Task 1: Add the shared string crypto contract

**Files:**
- Modify: `settings.gradle.kts`
- Create: `harden-string-crypto/build.gradle.kts`
- Create: `harden-string-crypto/src/main/kotlin/com/apkharden/crypto/StringCrypto.kt`
- Create: `harden-string-crypto/src/test/kotlin/com/apkharden/crypto/StringCryptoTest.kt`

- [x] Derive a 256-bit key from two build fragments, current certificate
  SHA-256, applicationId, and buildId using a versioned, length-delimited input.
- [x] Encrypt/decrypt UTF-8 strings with AES-256-GCM, 12-byte IVs, 128-bit tags,
  and caller-provided AAD.
- [x] Reject malformed keys, IVs, certificate hashes, and tampered inputs.
- [x] Keep the module free of Android and Gradle dependencies.
- [x] Commit `feat(crypto): add certificate-bound string encryption`.

## Task 2: Add lazy runtime string decoding

**Files:**
- Modify: `harden-runtime/build.gradle.kts`
- Create: `harden-runtime/src/main/kotlin/com/apkharden/runtime/HardenStringTable.kt`
- Create: `harden-runtime/src/main/kotlin/com/apkharden/runtime/HardenStrings.kt`
- Create: `harden-runtime/src/test/kotlin/com/apkharden/runtime/HardenStringsTest.kt`

- [x] Initialize only after runtime signer verification succeeds.
- [x] Require exactly one generated table and validate all table dimensions.
- [x] Decode by integer id and cache plaintext in `AtomicReferenceArray`.
- [x] Never persist plaintext or eagerly decrypt the complete table.
- [x] Clear key material on initialization failure and process termination.

## Task 3: Generate per-variant encrypted string tables

**Files:**
- Modify: plugin extension and variant generation tasks.
- Create: string collection/table generation tasks and tests.

- [x] Default protected package prefixes to applicationId when none are set.
- [x] Generate secure random key fragments and one independent IV per entry.
- [x] Bind entry ids as AES-GCM AAD and generate a standard table class.
- [x] Keep plaintext, keys, passwords, and private material out of metadata and
  Gradle logs.
- [x] Make task inputs/outputs explicit and cache behavior correct for randomness.

## Task 4: Transform eligible method-body strings

- [x] Instrument project classes only.
- [x] Replace eligible `LDC String` instructions with integer id plus
  `HardenStrings.decode(int)`.
- [x] Exclude runtime/generated classes, R/BuildConfig/DataBinding, Application
  constructors/static initializers, AppComponentFactory, and configured rules.
- [ ] Preserve frames and verify transformed bytecode with ASM.

## Task 5: Transform safe constant fields

- [x] Remove ConstantValue only when dynamic initialization is proven safe.
- [x] Generate or extend `<clinit>` with decoded assignments.
- [ ] Report public constant semantic changes.
- [x] Exclude uncertain fields instead of forcing transformation.

## Task 6: Add framework-aware exclusions and reports

- [x] Exclude annotation values and known Retrofit, Room, serialization, JNI,
  reflection, ServiceLoader, and resource-name contracts.
- [ ] Produce per-variant protected/excluded counts and stable reason codes.
- [x] Support explicit class and string allowlists.

## Task 7: Certify fixtures and plaintext removal

- [ ] Cover Java, Kotlin, coroutine, Compose, reflection, serialization, Room,
  Retrofit, JNI, custom/default Application, and R8 on/off fixtures.
- [ ] Assert protected plaintext is absent from class and DEX scans.
- [ ] Assert excluded framework strings remain unchanged.
- [ ] Verify ciphertext tampering fails authentication.
- [ ] Measure startup, decode, size, and cache memory thresholds.

Phase 3 may be implemented while deferred device certification is pending, but
it must not be marked release-qualified until the mandatory device matrix also
passes.
