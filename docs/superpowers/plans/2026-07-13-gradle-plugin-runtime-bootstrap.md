# Gradle Plugin and Runtime Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Every behavior change follows red-green-refactor TDD and uses checkbox tracking.

**Goal:** Add a production Android runtime and Gradle plugin that supports every application variant, works with R8 on or off, initializes through the real Application lifecycle, validates the production signer, enforces anti-debug checks, and never references Android hidden APIs.

**Architecture:** `harden-runtime` is a pure Java/Kotlin Android library with no native code. `harden-gradle-plugin` compiles against AGP 8.5.1 public APIs, discovers all application variants, generates per-variant metadata/configuration, transforms the merged manifest only when the app has no custom Application, and uses AGP ASM instrumentation to inject runtime installation into custom Application classes. TestKit fixture projects prove `product_32`, `product_64`, and `product_all` behavior with R8 disabled and enabled.

**Tech Stack:** Kotlin 2.1, Android Gradle Plugin 8.5.1 public API baseline, Gradle TestKit, ASM 9, Android compileSdk 36/minSdk 23, JUnit 5, Android instrumented tests.

---

## Task 1: Add runtime and plugin modules

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`
- Create: `harden-runtime/build.gradle.kts`
- Create: `harden-runtime/src/main/AndroidManifest.xml`
- Create: `harden-runtime/src/test/kotlin/com/apkharden/runtime/RuntimeModuleTest.kt`
- Create: `harden-gradle-plugin/build.gradle.kts`
- Create: `harden-gradle-plugin/src/test/kotlin/com/apkharden/gradle/PluginModuleTest.kt`

- [ ] Write module smoke tests asserting Java 17 and runtime/plugin version constants.
- [ ] Run `./gradlew :harden-runtime:test :harden-gradle-plugin:test` and verify projects are absent.
- [ ] Register both modules. Add Android library plugin 8.5.1 with `apply false` at the root. Configure runtime `compileSdk=36`, `minSdk=23`, namespace `com.apkharden.runtime`, no BuildConfig, Java/Kotlin 17. Configure plugin with `java-gradle-plugin`, `kotlin-dsl`, compileOnly AGP 8.5.1, TestKit and JUnit 5.
- [ ] Register plugin id `com.apkharden.production` implemented by `com.apkharden.gradle.ApkHardenPlugin`.
- [ ] Run both module tests and root tests.
- [ ] Commit `build: add production plugin and runtime modules`.

## Task 2: Implement runtime configuration and certificate verification

**Files:**
- Create: `harden-runtime/src/main/java/com/apkharden/runtime/HardenConfig.java`
- Create: `harden-runtime/src/main/java/com/apkharden/runtime/CertificateVerifier.java`
- Create: `harden-runtime/src/test/kotlin/com/apkharden/runtime/HardenConfigTest.kt`
- Create: `harden-runtime/src/androidTest/java/com/apkharden/runtime/CertificateVerifierTest.java`

- [ ] Write JVM tests requiring `HardenConfig` to reject blank application id/build id and require exactly 64 lowercase hex certificate characters.
- [ ] Implement immutable `HardenConfig(applicationId, variantName, buildId, certificateSha256)` with defensive validation and no password/private-key fields.
- [ ] Write device tests for API 23 legacy signatures and API 28+ `SigningInfo`, requiring exactly one current signer and exact SHA-256 equality.
- [ ] Implement `CertificateVerifier.verify(Context,HardenConfig)` using only public PackageManager APIs; do not accept signing history or multiple signers.
- [ ] Run runtime unit/device compile tests and commit `feat(runtime): verify production certificate`.

## Task 3: Implement fixed anti-debug checks and one-time runtime install

**Files:**
- Create: `harden-runtime/src/main/java/com/apkharden/runtime/AntiDebug.java`
- Create: `harden-runtime/src/main/java/com/apkharden/runtime/HardenRuntime.java`
- Create: `harden-runtime/src/main/java/com/apkharden/runtime/HardenFailure.java`
- Create: `harden-runtime/src/androidTest/java/com/apkharden/runtime/HardenRuntimeTest.java`

- [ ] Write device tests proving debuggable apps are rejected, valid release-like context initializes once, repeated calls are idempotent, and failure records contain only code/runtime/process.
- [ ] Implement checks for `FLAG_DEBUGGABLE`, `Debug.isDebuggerConnected`, `Debug.waitingForDebugger`, and `/proc/self/status` `TracerPid`.
- [ ] Implement `HardenRuntime.install(Application,HardenConfig)` with an AtomicBoolean per process, certificate verification before provider initialization, private diagnostic file writing, and process termination through an injectable terminator used by tests.
- [ ] Scan runtime bytecode/source for hidden API names and commit `feat(runtime): add certificate-bound anti-debug bootstrap`.

## Task 4: Define plugin extension and discover every application variant

**Files:**
- Create: `harden-gradle-plugin/src/main/kotlin/com/apkharden/gradle/ApkHardenExtension.kt`
- Create: `harden-gradle-plugin/src/main/kotlin/com/apkharden/gradle/ApkHardenPlugin.kt`
- Create: `harden-gradle-plugin/src/main/kotlin/com/apkharden/gradle/VariantDescriptor.kt`
- Create: `harden-gradle-plugin/src/test/kotlin/com/apkharden/gradle/VariantDescriptorTest.kt`

- [ ] Write tests for defaults: enabled true, empty excludedVariants, R8 AUTO, empty protectedPackages, required certificate provider.
- [ ] Implement extension with Gradle lazy `Property`/`SetProperty` APIs.
- [ ] Apply only to `com.android.application`; fail with a clear message on library projects.
- [ ] Use `androidComponents.selector().all()` and `onVariants` without name assumptions. Capture name, buildType, productFlavors, applicationId, minSdk, targetSdk and ABI filters in `VariantDescriptor`.
- [ ] Ensure excluded variants are skipped only by explicit exact name.
- [ ] Commit `feat(plugin): discover all Android application variants`.

## Task 5: Generate per-variant metadata and config classes

**Files:**
- Create: `harden-gradle-plugin/src/main/kotlin/com/apkharden/gradle/task/GenerateHardenMetadataTask.kt`
- Create: `harden-gradle-plugin/src/main/kotlin/com/apkharden/gradle/task/GenerateHardenConfigTask.kt`
- Create: `harden-gradle-plugin/src/test/kotlin/com/apkharden/gradle/task/GenerateHardenMetadataTaskTest.kt`

- [ ] Write task tests for `product_32`, `product_64`, `product_all`, R8 true/false, deterministic schema fields, 64-char certificate normalization and unique build id.
- [ ] Generate `harden-metadata.json` matching release-core schema 1.
- [ ] Generate `com.apkharden.generated.HardenVariantConfig` Java source containing schema/runtime/plugin/variant/application/version/certificate/build fields.
- [ ] Register generated Java source through the variant Sources API and metadata as a variant output artifact.
- [ ] Never place keystore passwords or private key material in task inputs, outputs or Gradle logs.
- [ ] Commit `feat(plugin): generate variant hardening metadata`.

## Task 6: Inject custom Application initialization using AGP ASM

**Files:**
- Create: `harden-gradle-plugin/src/main/kotlin/com/apkharden/gradle/instrumentation/ApplicationInstallVisitorFactory.kt`
- Create: `harden-gradle-plugin/src/main/kotlin/com/apkharden/gradle/instrumentation/ApplicationInstallVisitor.kt`
- Create: `harden-gradle-plugin/src/test/kotlin/com/apkharden/gradle/instrumentation/ApplicationInstallVisitorTest.kt`

- [ ] Write ASM tests for Java/Kotlin Applications with and without `attachBaseContext`, inherited base Applications, multiple normal returns, and duplicate-plugin execution.
- [ ] Read the transformed merged manifest as a task/visitor parameter to identify the exact custom Application class.
- [ ] Instrument only that class using `InstrumentationScope.ALL`; string protection remains PROJECT-only in Phase 3.
- [ ] Existing `attachBaseContext` receives one `HardenRuntime.install(this,HardenVariantConfig.INSTANCE)` call immediately before every normal RETURN. Missing method is generated and invokes the real superclass before install.
- [ ] Add a stable bytecode marker and skip duplicate injection.
- [ ] Use AGP frame computation for instrumented methods and ASM verification tests.
- [ ] Commit `feat(plugin): inject runtime into custom Application`.

## Task 7: Support apps without a custom Application

**Files:**
- Create: `harden-runtime/src/main/java/com/apkharden/runtime/HardenApplication.java`
- Create: `harden-gradle-plugin/src/main/kotlin/com/apkharden/gradle/task/TransformHardenManifestTask.kt`
- Create: `harden-gradle-plugin/src/test/kotlin/com/apkharden/gradle/task/TransformHardenManifestTaskTest.kt`

- [ ] Write manifest tests proving an absent Application name becomes `com.apkharden.runtime.HardenApplication`, a custom name remains unchanged, AppComponentFactory remains byte-for-byte unchanged, and debuggable/testOnly are not silently altered.
- [ ] Implement `HardenApplication.attachBaseContext` calling super then runtime install with generated config.
- [ ] Transform the merged manifest through the public Artifact API; do not edit source manifests.
- [ ] Preserve providers, processes, authorities, exported flags, permissions and intent filters.
- [ ] Commit `feat(plugin): bootstrap apps without custom Application`.

## Task 8: Add runtime dependency and R8-optional behavior

**Files:**
- Create: `harden-runtime/consumer-rules.pro`
- Modify: `harden-gradle-plugin/src/main/kotlin/com/apkharden/gradle/ApkHardenPlugin.kt`
- Create: `harden-gradle-plugin/src/test/kotlin/com/apkharden/gradle/R8PolicyTest.kt`

- [ ] Write tests proving the plugin never changes `minifyEnabled`, `shrinkResources` or business ProGuard files.
- [ ] Add runtime dependency to application `implementation` without variant-name guessing.
- [ ] Implement `R8Policy.AUTO` and `IGNORE`; neither may enable R8.
- [ ] Supply only minimal consumer keep rules for runtime entry/config references.
- [ ] Commit `feat(plugin): support R8 on and off without mutation`.

## Task 9: Build real TestKit fixture variants

**Files:**
- Create: `harden-gradle-plugin/src/test/fixtures/android-app/...`
- Create: `harden-gradle-plugin/src/test/kotlin/com/apkharden/gradle/ProductionPluginFunctionalTest.kt`

- [ ] Create a real Android app fixture with variants/build types named to produce `product_32`, `product_64`, `product_all`, debug/develop/release combinations, custom Application, custom AppComponentFactory, provider and secondary process.
- [ ] Run fixtures with R8 disabled and enabled; assert the plugin discovers every actual variant and does not infer ABI from variant names.
- [ ] Inspect APK/manifest/classes to prove real Application identity, AppComponentFactory, provider/process declarations and ABI contents are preserved.
- [ ] Assert each output has matching metadata and generated config certificate.
- [ ] Commit `test(plugin): cover all variants and R8 modes`.

## Task 10: Device smoke tests and phase verification

Execution status (2026-07-14): runner, fixture, multi-process instrumentation,
and local JVM/TestKit coverage are implemented. Real-device execution is
deferred; keep Phase 2 open until API 26 and API 36/16KB plus wrong-signer and
debugger-termination scenarios pass on the required devices.

**Files:**
- Create: `harden-device-tests/src/main/kotlin/com/apkharden/device/RuntimeSmokeRunner.kt`
- Create: `harden-runtime/src/androidTest/java/com/apkharden/runtime/MultiProcessRuntimeTest.java`
- Modify: roadmap phase status after verification.

- [ ] Build fixture APKs for API 26 and API 36/16KB.
- [ ] Install and launch custom/default Application variants; assert process survives and launcher resumes.
- [ ] Start secondary process components and assert runtime installs exactly once per process.
- [ ] Re-sign with a different test certificate and assert runtime terminates.
- [ ] Start with `am start -D` and assert anti-debug termination without ANR/restart loop.
- [ ] Run `./gradlew clean test :harden-runtime:test :harden-gradle-plugin:test` and hidden API scans.
- [ ] Commit `test: certify runtime bootstrap phase` and mark Phase 2 complete only after all checks pass.
