# Release Core Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a standalone JVM release-verification core that proves whether an online APK, a candidate APK, hardening metadata, and a production keystore satisfy the static requirements for a safe direct-signature update.

**Architecture:** Add `harden-release-core` without moving the current desktop or experimental hardening code. The module reads APK identity/signatures, applies stable gates, verifies 16KB ZIP/ELF compatibility, signs unsigned candidates without content repackaging, and emits JSON reports.

**Tech Stack:** Kotlin/JVM 2.1, JDK 17, Gradle Kotlin DSL, apksig 8.3.2, ARSCLib 1.3.8, kotlinx.serialization 1.6.3, JUnit 5.

---

## File Map

```text
harden-release-core/
├─ build.gradle.kts
├─ src/main/kotlin/com/apkharden/release/
│  ├─ model/ReleaseModels.kt
│  ├─ metadata/HardenMetadata.kt
│  ├─ metadata/HardenMetadataReader.kt
│  ├─ crypto/CertificateDigests.kt
│  ├─ crypto/KeystoreReader.kt
│  ├─ apk/ApkSignatureReader.kt
│  ├─ apk/ApkIdentityReader.kt
│  ├─ apk/ZipAlignmentInspector.kt
│  ├─ apk/ElfAlignmentInspector.kt
│  ├─ gate/ReleaseGateEvaluator.kt
│  ├─ gate/ReleaseAnalyzer.kt
│  ├─ signing/ReleaseSigner.kt
│  ├─ report/ReleaseReportWriter.kt
│  └─ cli/ReleaseCheckCli.kt
└─ src/test/kotlin/com/apkharden/release/...
```

Do not move existing `com.apkharden.packager.core` files in this phase.

---

### Task 1: Add the release-core module

**Files:**
- Create: `harden-release-core/src/test/kotlin/com/apkharden/release/ModuleSmokeTest.kt`
- Create: `harden-release-core/build.gradle.kts`
- Modify: `settings.gradle.kts`

- [ ] **Step 1: Write the smoke test before registering the module**

```kotlin
package com.apkharden.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ModuleSmokeTest {
    @Test fun `release core runs on Java 17`() {
        assertEquals(17, Runtime.version().feature())
    }
}
```

- [ ] **Step 2: Verify the module is initially absent**

Run: `.\gradlew.bat :harden-release-core:test`

Expected: FAIL with `project 'harden-release-core' not found`.

- [ ] **Step 3: Register and configure the module**

Append to `settings.gradle.kts`:

```kotlin
include(":harden-release-core")
```

Create `harden-release-core/build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("com.android.tools.build:apksig:8.3.2")
    implementation("io.github.reandroid:ARSCLib:1.3.8")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

kotlin { jvmToolchain(17) }
tasks.test { useJUnitPlatform() }
```

- [ ] **Step 4: Run module and root baselines**

Run:

```powershell
.\gradlew.bat :harden-release-core:test
.\gradlew.bat test
```

Expected: module smoke test passes and the existing 36 root tests remain green.

- [ ] **Step 5: Commit**

```powershell
git add settings.gradle.kts harden-release-core
git commit -m "build: add release core module"
```

---

### Task 2: Define stable assessment models

**Files:**
- Create: `harden-release-core/src/main/kotlin/com/apkharden/release/model/ReleaseModels.kt`
- Create: `harden-release-core/src/test/kotlin/com/apkharden/release/model/ReleaseModelsTest.kt`

- [ ] **Step 1: Write failing status tests**

```kotlin
package com.apkharden.release.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReleaseModelsTest {
    @Test fun `blocker prevents static verification`() {
        val result = ReleaseAssessment(
            listOf(ReleaseFinding("PACKAGE_MISMATCH", FindingLevel.BLOCKER, "diff"))
        )
        assertEquals(ReleaseStatus.NOT_QUALIFIED, result.status)
    }

    @Test fun `approval finding requires matching approval code`() {
        val finding = ReleaseFinding(
            "MIN_SDK_INCREASED", FindingLevel.REQUIRES_APPROVAL, "raised"
        )
        assertEquals(ReleaseStatus.NOT_QUALIFIED, ReleaseAssessment(listOf(finding)).status)
        assertEquals(
            ReleaseStatus.STATIC_VERIFIED,
            ReleaseAssessment(listOf(finding), setOf("MIN_SDK_INCREASED")).status,
        )
    }
}
```

- [ ] **Step 2: Run and verify compilation fails**

Run: `.\gradlew.bat :harden-release-core:test --tests "*.ReleaseModelsTest"`

Expected: unresolved model types.

- [ ] **Step 3: Implement the model API**

```kotlin
package com.apkharden.release.model

import kotlinx.serialization.Serializable
import java.io.File

@Serializable enum class FindingLevel { BLOCKER, REQUIRES_APPROVAL, WARNING, INFO }
@Serializable enum class ReleaseStatus { NOT_QUALIFIED, STATIC_VERIFIED, DEVICE_VERIFIED, RELEASE_QUALIFIED }

@Serializable
data class ReleaseFinding(
    val code: String,
    val level: FindingLevel,
    val message: String,
    val details: Map<String, String> = emptyMap(),
)

@Serializable
data class ApkSignatureInfo(
    val verified: Boolean,
    val signerSha256: Set<String>,
    val signerCount: Int,
    val hasSigningLineage: Boolean,
    val v1: Boolean,
    val v2: Boolean,
    val v3: Boolean,
    val v31: Boolean,
    val errors: List<String> = emptyList(),
)

@Serializable
data class ApkIdentity(
    val fileName: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int,
    val targetSdk: Int,
    val debuggable: Boolean,
    val testOnly: Boolean,
    val splitName: String?,
    val extractNativeLibs: Boolean?,
    val requiredFeatures: Set<String>,
    val abis: Set<String>,
    val signature: ApkSignatureInfo,
)

@Serializable
data class KeystoreIdentity(
    val alias: String,
    val certificateSha256: String,
    val certificateSubject: String,
)

data class KeystoreRequest(
    val file: File,
    val storePassword: CharArray,
    val alias: String,
    val keyPassword: CharArray,
)

data class ReleaseRequest(
    val onlineApk: File,
    val candidateApk: File,
    val metadataFile: File,
    val keystore: KeystoreRequest,
    val approvedFindingCodes: Set<String> = emptySet(),
)

@Serializable
data class ReleaseAssessment(
    val findings: List<ReleaseFinding>,
    val approvedFindingCodes: Set<String> = emptySet(),
    val online: ApkIdentity? = null,
    val candidate: ApkIdentity? = null,
    val keystore: KeystoreIdentity? = null,
    val status: ReleaseStatus = calculateStatus(findings, approvedFindingCodes),
) {
    companion object {
        private fun calculateStatus(
            findings: List<ReleaseFinding>,
            approved: Set<String>,
        ): ReleaseStatus = when {
            findings.any { it.level == FindingLevel.BLOCKER } -> ReleaseStatus.NOT_QUALIFIED
            findings.any {
                it.level == FindingLevel.REQUIRES_APPROVAL && it.code !in approved
            } -> ReleaseStatus.NOT_QUALIFIED
            else -> ReleaseStatus.STATIC_VERIFIED
        }
    }
}
```

- [ ] **Step 4: Run the model tests**

Run: `.\gradlew.bat :harden-release-core:test --tests "*.ReleaseModelsTest"`

Expected: PASS, two tests.

- [ ] **Step 5: Commit**

```powershell
git add harden-release-core/src/main/kotlin/com/apkharden/release/model harden-release-core/src/test/kotlin/com/apkharden/release/model
git commit -m "feat(release): add assessment models"
```

---
### Task 3: Parse hardening metadata

**Files:**
- Create: `harden-release-core/src/main/kotlin/com/apkharden/release/metadata/HardenMetadata.kt`
- Create: `harden-release-core/src/main/kotlin/com/apkharden/release/metadata/HardenMetadataReader.kt`
- Create: `harden-release-core/src/test/kotlin/com/apkharden/release/metadata/HardenMetadataReaderTest.kt`

- [ ] **Step 1: Write failing schema tests**

```kotlin
package com.apkharden.release.metadata

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class HardenMetadataReaderTest {
    @TempDir lateinit var temp: File

    @Test fun `reads R8-disabled product variant metadata`() {
        val file = File(temp, "metadata.json")
        file.writeText(
            """{"schemaVersion":1,"pluginVersion":"1.0.0","runtimeVersion":"1.0.0","variantName":"product_32","applicationId":"com.example.app","versionCode":120,"minSdk":23,"targetSdk":36,"debuggable":false,"r8Enabled":false,"abis":["armeabi-v7a"],"expectedCertificateSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","buildId":"build-1"}"""
        )
        val metadata = HardenMetadataReader.read(file)
        assertEquals("product_32", metadata.variantName)
        assertEquals(false, metadata.r8Enabled)
    }

    @Test fun `rejects unsupported schema`() {
        val file = File(temp, "metadata.json").apply {
            writeText("""{"schemaVersion":2}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HardenMetadataReader.read(file)
        }
    }
}
```

- [ ] **Step 2: Run and verify missing metadata types**

Run: `.\gradlew.bat :harden-release-core:test --tests "*.HardenMetadataReaderTest"`

Expected: compilation FAIL.

- [ ] **Step 3: Implement schema and strict reader**

```kotlin
package com.apkharden.release.metadata

import kotlinx.serialization.Serializable

@Serializable
data class HardenMetadata(
    val schemaVersion: Int,
    val pluginVersion: String,
    val runtimeVersion: String,
    val variantName: String,
    val applicationId: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val debuggable: Boolean,
    val r8Enabled: Boolean,
    val abis: Set<String>,
    val expectedCertificateSha256: String,
    val buildId: String,
)
```

```kotlin
package com.apkharden.release.metadata

import kotlinx.serialization.json.Json
import java.io.File

object HardenMetadataReader {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false }

    fun read(file: File): HardenMetadata {
        require(file.isFile) { "Metadata file not found: $file" }
        val value = json.decodeFromString<HardenMetadata>(file.readText())
        require(value.schemaVersion == 1) { "Unsupported metadata schema: ${value.schemaVersion}" }
        require(value.applicationId.isNotBlank()) { "Metadata applicationId is blank" }
        require(value.variantName.isNotBlank()) { "Metadata variantName is blank" }
        require(value.expectedCertificateSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            "Metadata certificate SHA-256 is malformed"
        }
        return value.copy(expectedCertificateSha256 = value.expectedCertificateSha256.lowercase())
    }
}
```

- [ ] **Step 4: Run tests and commit**

Run: `.\gradlew.bat :harden-release-core:test --tests "*.HardenMetadataReaderTest"`

Expected: PASS, two tests.

```powershell
git add harden-release-core/src/main/kotlin/com/apkharden/release/metadata harden-release-core/src/test/kotlin/com/apkharden/release/metadata
git commit -m "feat(release): parse hardening metadata"
```

---

### Task 4: Read keystore and APK signer identities

**Files:**
- Create: `harden-release-core/src/main/kotlin/com/apkharden/release/crypto/CertificateDigests.kt`
- Create: `harden-release-core/src/main/kotlin/com/apkharden/release/crypto/KeystoreReader.kt`
- Create: `harden-release-core/src/main/kotlin/com/apkharden/release/apk/ApkSignatureReader.kt`
- Create: `harden-release-core/src/test/kotlin/com/apkharden/release/fixture/TestApkFactory.kt`
- Create: `harden-release-core/src/test/kotlin/com/apkharden/release/crypto/KeystoreReaderTest.kt`
- Create: `harden-release-core/src/test/kotlin/com/apkharden/release/apk/ApkSignatureReaderTest.kt`

- [ ] **Step 1: Write failing signer tests**

```kotlin
package com.apkharden.release.crypto

import com.apkharden.release.model.KeystoreRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class KeystoreReaderTest {
    @Test fun `loads leaf certificate identity`() {
        val loaded = KeystoreReader.load(
            KeystoreRequest(
                File("../src/test/resources/test.jks"),
                "123456".toCharArray(), "test", "123456".toCharArray(),
            )
        )
        assertEquals(64, loaded.identity.certificateSha256.length)
        assertEquals("CN=ApkHarden Test", loaded.identity.certificateSubject)
    }
}
```

```kotlin
package com.apkharden.release.apk

import com.apkharden.release.fixture.TestApkFactory
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ApkSignatureReaderTest {
    @TempDir lateinit var temp: File

    @Test fun `unsigned APK returns an unverified identity`() {
        val apk = TestApkFactory.createUnsigned(temp, "com.example.unsigned", 1)
        assertFalse(ApkSignatureReader.read(apk).verified)
    }

    @Test fun `signed APK reports one V1 V2 V3 signer`() {
        val unsigned = TestApkFactory.createUnsigned(temp, "com.example.signed", 1)
        val info = ApkSignatureReader.read(
            TestApkFactory.sign(unsigned, File(temp, "signed.apk"))
        )
        assertTrue(info.verified)
        assertTrue(info.v1 && info.v2 && info.v3)
        assertTrue(info.signerCount == 1 && info.signerSha256.single().length == 64)
    }
}
```

- [ ] **Step 2: Run and verify compilation fails**

Run:

```powershell
.\gradlew.bat :harden-release-core:test --tests "*.KeystoreReaderTest" --tests "*.ApkSignatureReaderTest"
```

Expected: compilation FAIL.

- [ ] **Step 3: Implement certificate and keystore readers**

```kotlin
package com.apkharden.release.crypto

import java.security.MessageDigest
import java.security.cert.X509Certificate

object CertificateDigests {
    fun sha256(cert: X509Certificate): String = MessageDigest.getInstance("SHA-256")
        .digest(cert.encoded)
        .joinToString("") { "%02x".format(it) }
}
```

```kotlin
package com.apkharden.release.crypto

import com.apkharden.release.model.KeystoreIdentity
import com.apkharden.release.model.KeystoreRequest
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

class LoadedKeystore(
    val identity: KeystoreIdentity,
    val privateKey: PrivateKey,
    val certificateChain: List<X509Certificate>,
)

object KeystoreReader {
    fun load(request: KeystoreRequest): LoadedKeystore {
        require(request.file.isFile) { "Keystore not found: ${request.file}" }
        val store = listOf("JKS", "PKCS12").firstNotNullOfOrNull { type ->
            runCatching {
                KeyStore.getInstance(type).also { ks ->
                    request.file.inputStream().use { ks.load(it, request.storePassword) }
                }
            }.getOrNull()
        } ?: throw IllegalArgumentException("Unable to read keystore as JKS or PKCS12")
        val key = store.getKey(request.alias, request.keyPassword) as? PrivateKey
            ?: throw IllegalArgumentException("No private key for alias '${request.alias}'")
        val chain = store.getCertificateChain(request.alias)?.map { it as X509Certificate }
            ?: throw IllegalArgumentException("No certificate chain for alias '${request.alias}'")
        return LoadedKeystore(
            KeystoreIdentity(
                request.alias,
                CertificateDigests.sha256(chain.first()),
                chain.first().subjectX500Principal.name,
            ),
            key,
            chain,
        )
    }
}
```

- [ ] **Step 4: Implement APK signature inspection**

```kotlin
package com.apkharden.release.apk

import com.android.apksig.ApkVerifier
import com.apkharden.release.crypto.CertificateDigests
import com.apkharden.release.model.ApkSignatureInfo
import java.io.File

object ApkSignatureReader {
    fun read(apk: File): ApkSignatureInfo {
        require(apk.isFile) { "APK not found: $apk" }
        val result = ApkVerifier.Builder(apk).build().verify()
        return ApkSignatureInfo(
            verified = result.isVerified,
            signerSha256 = result.signerCertificates.map(CertificateDigests::sha256).toSet(),
            signerCount = result.signerCertificates.size,
            hasSigningLineage = result.signingCertificateLineage != null,
            v1 = result.isVerifiedUsingV1Scheme,
            v2 = result.isVerifiedUsingV2Scheme,
            v3 = result.isVerifiedUsingV3Scheme,
            v31 = result.isVerifiedUsingV31Scheme,
            errors = result.allErrors.map(Object::toString),
        )
    }
}
```

- [ ] **Step 5: Add a real binary-manifest APK fixture**

Create `TestApkFactory.kt`:

```kotlin
package com.apkharden.release.fixture

import com.android.apksig.ApkSigner
import com.apkharden.release.crypto.KeystoreReader
import com.apkharden.release.model.KeystoreRequest
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object TestApkFactory {
    private val testKey = File("../src/test/resources/test.jks")

    fun createUnsigned(
        directory: File,
        packageName: String,
        versionCode: Int,
        minSdk: Int = 23,
        targetSdk: Int = 36,
        debuggable: Boolean = false,
        testOnly: Boolean = false,
        splitName: String? = null,
        abis: Set<String> = emptySet(),
    ): File {
        val block = AndroidManifestBlock().apply {
            this.packageName = packageName
            this.versionCode = versionCode
            this.minSdkVersion = minSdk
            this.targetSdkVersion = targetSdk
            this.isDebuggable = debuggable
            if (splitName != null) setSplit(splitName, false)
            val app = getOrCreateApplicationElement()
            if (testOnly) {
                app.getOrCreateAndroidAttribute("testOnly", 0x01010272)
                    .valueAsBoolean = true
            }
            refreshFull()
        }
        return File(directory, "$packageName-$versionCode.apk").also { output ->
            ZipOutputStream(output.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml")); zip.write(block.bytes); zip.closeEntry()
                zip.putNextEntry(ZipEntry("classes.dex")); zip.write(ByteArray(128)); zip.closeEntry()
                abis.forEach { abi ->
                    zip.putNextEntry(ZipEntry("lib/$abi/libfixture.so")); zip.write(ByteArray(256)); zip.closeEntry()
                }
            }
        }
    }

    fun sign(input: File, output: File): File {
        val key = KeystoreReader.load(
            KeystoreRequest(testKey, "123456".toCharArray(), "test", "123456".toCharArray())
        )
        val config = ApkSigner.SignerConfig.Builder("TEST", key.privateKey, key.certificateChain).build()
        ApkSigner.Builder(listOf(config))
            .setInputApk(input).setOutputApk(output)
            .setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(true)
            .build().sign()
        return output
    }
}
```

- [ ] **Step 6: Run tests and commit**

Run:

```powershell
.\gradlew.bat :harden-release-core:test --tests "*.KeystoreReaderTest" --tests "*.ApkSignatureReaderTest"
```

Expected: PASS, three tests.

```powershell
git add harden-release-core/src/main/kotlin/com/apkharden/release/crypto harden-release-core/src/main/kotlin/com/apkharden/release/apk/ApkSignatureReader.kt harden-release-core/src/test/kotlin/com/apkharden/release
git commit -m "feat(release): inspect APK and keystore signers"
```

---

### Task 5: Read APK identity and ABI information

**Files:**
- Create: `harden-release-core/src/main/kotlin/com/apkharden/release/apk/ApkIdentityReader.kt`
- Create: `harden-release-core/src/test/kotlin/com/apkharden/release/apk/ApkIdentityReaderTest.kt`

- [ ] **Step 1: Write failing identity tests**

```kotlin
package com.apkharden.release.apk

import com.apkharden.release.fixture.TestApkFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ApkIdentityReaderTest {
    @TempDir lateinit var temp: File

    @Test fun `reads package version flags and ABI`() {
        val input = TestApkFactory.createUnsigned(
            temp, "com.example.product64", 120, debuggable = true,
            abis = setOf("arm64-v8a"),
        )
        val apk = TestApkFactory.sign(input, File(temp, "signed.apk"))
        val value = ApkIdentityReader.read(apk)
        assertEquals("com.example.product64", value.packageName)
        assertEquals(120L, value.versionCode)
        assertTrue(value.debuggable)
        assertEquals(setOf("arm64-v8a"), value.abis)
    }

    @Test fun `detects split and testOnly`() {
        val apk = TestApkFactory.createUnsigned(
            temp, "com.example.split", 2,
            testOnly = true, splitName = "config.arm64_v8a",
        )
        val value = ApkIdentityReader.read(apk)
        assertEquals("config.arm64_v8a", value.splitName)
        assertTrue(value.testOnly)
    }
}
```

- [ ] **Step 2: Run and verify reader is missing**

Run: `.\gradlew.bat :harden-release-core:test --tests "*.ApkIdentityReaderTest"`

Expected: compilation FAIL.

- [ ] **Step 3: Implement identity parsing**

```kotlin
package com.apkharden.release.apk

import com.apkharden.release.model.ApkIdentity
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File
import java.util.zip.ZipFile

object ApkIdentityReader {
    private const val ATTR_NAME = 0x01010003
    private val nativeEntry = Regex("lib/([^/]+)/[^/]+\\.so")

    fun read(apk: File): ApkIdentity = ZipFile(apk).use { zip ->
        val manifestEntry = zip.getEntry("AndroidManifest.xml")
            ?: throw IllegalArgumentException("APK has no AndroidManifest.xml")
        val manifest = zip.getInputStream(manifestEntry).use { AndroidManifestBlock.load(it) }
        val app = manifest.applicationElement
            ?: throw IllegalArgumentException("Manifest has no application")
        val features = manifest.manifestElement.listElements("uses-feature")
            .filter { it.searchAttributeByName("required")?.valueAsBoolean != false }
            .mapNotNull { it.searchAttributeByResourceId(ATTR_NAME)?.valueAsString }
            .toSortedSet()
        val abis = zip.entries().asSequence()
            .mapNotNull { nativeEntry.matchEntire(it.name)?.groupValues?.get(1) }
            .toSortedSet()
        ApkIdentity(
            fileName = apk.name,
            packageName = requireNotNull(manifest.packageName) { "Manifest package missing" },
            versionCode = requireNotNull(manifest.versionCode) { "versionCode missing" }.toLong(),
            versionName = manifest.versionName,
            minSdk = manifest.minSdkVersion ?: 1,
            targetSdk = manifest.targetSdkVersion ?: 1,
            debuggable = manifest.isDebuggable,
            testOnly = app.searchAttributeByName("testOnly")?.valueAsBoolean == true,
            splitName = manifest.split,
            extractNativeLibs = manifest.isExtractNativeLibs,
            requiredFeatures = features,
            abis = abis,
            signature = ApkSignatureReader.read(apk),
        )
    }
}
```

- [ ] **Step 4: Run tests and commit**

Run: `.\gradlew.bat :harden-release-core:test --tests "*.ApkIdentityReaderTest"`

Expected: PASS, two tests.

```powershell
git add harden-release-core/src/main/kotlin/com/apkharden/release/apk/ApkIdentityReader.kt harden-release-core/src/test/kotlin/com/apkharden/release/apk/ApkIdentityReaderTest.kt
git commit -m "feat(release): read APK release identity"
```

---
### Task 6: Enforce strict online-update gates

**Files:**
- Create: `harden-release-core/src/main/kotlin/com/apkharden/release/gate/ReleaseGateEvaluator.kt`
- Create: `harden-release-core/src/test/kotlin/com/apkharden/release/gate/ReleaseGateEvaluatorTest.kt`

- [ ] **Step 1: Write failing gate tests**

```kotlin
package com.apkharden.release.gate

import com.apkharden.release.metadata.HardenMetadata
import com.apkharden.release.model.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReleaseGateEvaluatorTest {
    private val certA = "a".repeat(64)
    private val certB = "b".repeat(64)
    private val certC = "c".repeat(64)

    private fun sig(digest: String = certA) = ApkSignatureInfo(
        true, setOf(digest), 1, false, true, true, true, false
    )

    private fun apk(
        pkg: String,
        version: Long,
        signer: String = certA,
        debuggable: Boolean = false,
        testOnly: Boolean = false,
        minSdk: Int = 23,
        split: String? = null,
        abis: Set<String> = setOf("arm64-v8a"),
        features: Set<String> = emptySet(),
    ) = ApkIdentity(
        "$pkg.apk", pkg, version, null, minSdk, 36,
        debuggable, testOnly, split, false, features, abis, sig(signer),
    )

    private fun metadata(pkg: String, version: Long, cert: String = certA) = HardenMetadata(
        1, "1.0.0", "1.0.0", "product_64", pkg, version,
        23, 36, false, false, setOf("arm64-v8a"), cert, "build-1",
    )

    @Test fun `valid direct-signature update has no findings`() {
        val findings = ReleaseGateEvaluator.evaluate(
            apk("com.example.app", 10),
            apk("com.example.app", 11),
            metadata("com.example.app", 11),
            KeystoreIdentity("release", certA, "CN=Release"),
        )
        assertEquals(emptyList<ReleaseFinding>(), findings)
    }

    @Test fun `identity version build flags and split are blockers`() {
        val findings = ReleaseGateEvaluator.evaluate(
            apk("com.example.old", 10),
            apk("com.example.new", 10, certB, true, true, 22, "config.arm64_v8a"),
            metadata("com.example.new", 10, certB),
            KeystoreIdentity("release", certC, "CN=Wrong"),
        )
        val codes = findings.map { it.code }.toSet()
        setOf(
            "PACKAGE_MISMATCH", "VERSION_NOT_INCREMENTED",
            "ONLINE_CERT_KEYSTORE_MISMATCH", "CANDIDATE_CERT_KEYSTORE_MISMATCH",
            "DEBUGGABLE_CANDIDATE", "TEST_ONLY_CANDIDATE",
            "MIN_SDK_UNSUPPORTED", "SPLIT_APK_UNSUPPORTED",
        ).forEach { assertTrue(it in codes, "missing $it") }
    }

    @Test fun `support reductions require explicit approval`() {
        val findings = ReleaseGateEvaluator.evaluate(
            apk("com.example.app", 10, minSdk = 23, abis = setOf("armeabi-v7a", "arm64-v8a")),
            apk("com.example.app", 11, minSdk = 26, abis = setOf("arm64-v8a"), features = setOf("android.hardware.camera")),
            metadata("com.example.app", 11),
            KeystoreIdentity("release", certA, "CN=Release"),
        )
        assertEquals(
            setOf("MIN_SDK_INCREASED", "ABI_SUPPORT_REDUCED", "REQUIRED_FEATURE_ADDED"),
            findings.filter { it.level == FindingLevel.REQUIRES_APPROVAL }.map { it.code }.toSet(),
        )
    }
}
```

- [ ] **Step 2: Run and verify evaluator is missing**

Run: `.\gradlew.bat :harden-release-core:test --tests "*.ReleaseGateEvaluatorTest"`

Expected: compilation FAIL.

- [ ] **Step 3: Implement all phase-one release rules**

```kotlin
package com.apkharden.release.gate

import com.apkharden.release.metadata.HardenMetadata
import com.apkharden.release.model.*

object ReleaseGateEvaluator {
    fun evaluate(
        online: ApkIdentity,
        candidate: ApkIdentity,
        metadata: HardenMetadata,
        keystore: KeystoreIdentity,
    ): List<ReleaseFinding> = buildList {
        blocker(!online.signature.verified, "ONLINE_APK_UNSIGNED", "Online APK signature is not verified")
        blocker(online.signature.signerCount != 1, "ONLINE_SIGNER_COUNT_UNSUPPORTED", "Online APK must have one signer")
        blocker(online.signature.hasSigningLineage, "SIGNING_LINEAGE_UNSUPPORTED", "Signing lineage is unsupported")
        blocker(online.packageName != candidate.packageName, "PACKAGE_MISMATCH", "Package names differ")
        blocker(candidate.versionCode <= online.versionCode, "VERSION_NOT_INCREMENTED", "versionCode must increase")
        blocker(candidate.debuggable, "DEBUGGABLE_CANDIDATE", "Candidate is debuggable")
        blocker(candidate.testOnly, "TEST_ONLY_CANDIDATE", "Candidate is testOnly")
        blocker(candidate.minSdk < 23, "MIN_SDK_UNSUPPORTED", "Candidate minSdk is below 23")
        blocker(candidate.splitName != null, "SPLIT_APK_UNSUPPORTED", "Candidate is a split APK")
        blocker(online.splitName != null, "ONLINE_SPLIT_APK_UNSUPPORTED", "Online baseline is a split APK")

        val onlineCert = online.signature.signerSha256.singleOrNull()
        blocker(onlineCert != keystore.certificateSha256, "ONLINE_CERT_KEYSTORE_MISMATCH", "Online APK and keystore certificates differ")
        if (candidate.signature.verified) {
            blocker(candidate.signature.signerCount != 1, "CANDIDATE_SIGNER_COUNT_UNSUPPORTED", "Candidate must have one signer")
            blocker(candidate.signature.hasSigningLineage, "CANDIDATE_SIGNING_LINEAGE_UNSUPPORTED", "Candidate signing lineage is unsupported")
            blocker(candidate.signature.signerSha256.singleOrNull() != keystore.certificateSha256, "CANDIDATE_CERT_KEYSTORE_MISMATCH", "Candidate and keystore certificates differ")
        }

        blocker(metadata.applicationId != candidate.packageName, "METADATA_PACKAGE_MISMATCH", "Metadata applicationId differs")
        blocker(metadata.versionCode != candidate.versionCode, "METADATA_VERSION_MISMATCH", "Metadata versionCode differs")
        blocker(metadata.minSdk != candidate.minSdk, "METADATA_MIN_SDK_MISMATCH", "Metadata minSdk differs")
        blocker(metadata.targetSdk != candidate.targetSdk, "METADATA_TARGET_SDK_MISMATCH", "Metadata targetSdk differs")
        blocker(metadata.debuggable != candidate.debuggable, "METADATA_DEBUGGABLE_MISMATCH", "Metadata debuggable differs")
        blocker(metadata.abis != candidate.abis, "METADATA_ABI_MISMATCH", "Metadata ABI set differs")
        blocker(metadata.expectedCertificateSha256 != keystore.certificateSha256, "METADATA_CERT_KEYSTORE_MISMATCH", "Metadata certificate differs")

        approval(candidate.minSdk > online.minSdk, "MIN_SDK_INCREASED", "Candidate increases minSdk")
        approval(!candidate.abis.containsAll(online.abis), "ABI_SUPPORT_REDUCED", "Candidate removes an online ABI")
        approval((candidate.requiredFeatures - online.requiredFeatures).isNotEmpty(), "REQUIRED_FEATURE_ADDED", "Candidate adds required hardware features")
    }

    private fun MutableList<ReleaseFinding>.blocker(condition: Boolean, code: String, message: String) {
        if (condition) add(ReleaseFinding(code, FindingLevel.BLOCKER, message))
    }

    private fun MutableList<ReleaseFinding>.approval(condition: Boolean, code: String, message: String) {
        if (condition) add(ReleaseFinding(code, FindingLevel.REQUIRES_APPROVAL, message))
    }
}
```

- [ ] **Step 4: Run tests and commit**

Run: `.\gradlew.bat :harden-release-core:test --tests "*.ReleaseGateEvaluatorTest"`

Expected: PASS, three tests.

```powershell
git add harden-release-core/src/main/kotlin/com/apkharden/release/gate harden-release-core/src/test/kotlin/com/apkharden/release/gate
git commit -m "feat(release): enforce direct-update gates"
```

---
### Task 7: Inspect 16KB ZIP alignment from central-directory offsets

**Files:**
- Create: `harden-release-core/src/main/kotlin/com/apkharden/release/apk/ZipAlignmentInspector.kt`
- Create: `harden-release-core/src/test/kotlin/com/apkharden/release/apk/ZipAlignmentInspectorTest.kt`

- [ ] **Step 1: Write failing aligned/misaligned tests**

```kotlin
package com.apkharden.release.apk

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipAlignmentInspectorTest {
    @TempDir lateinit var temp: File

    @Test fun `stored native entry at 4KB but not 16KB fails`() {
        val apk = nativeZip(File(temp, "4k.apk"), 4096)
        val entry = ZipAlignmentInspector.inspect(apk).single()
        assertTrue(entry.dataOffset % 4096L == 0L)
        assertFalse(entry.aligned16k)
    }

    @Test fun `stored native entry at 16KB passes`() {
        val apk = nativeZip(File(temp, "16k.apk"), 16384)
        assertTrue(ZipAlignmentInspector.inspect(apk).single().aligned16k)
    }

    private fun nativeZip(file: File, alignment: Int): File {
        val body = ByteArray(256) { it.toByte() }
        val counting = object : java.io.FilterOutputStream(file.outputStream()) {
            var count = 0L
            override fun write(b: Int) { out.write(b); count++ }
            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
        }
        ZipOutputStream(counting).use { zip ->
            val name = "lib/arm64-v8a/libfixture.so"
            val minExtra = 6
            val pad = ((alignment - ((counting.count + 30 + name.length + minExtra) % alignment)) % alignment).toInt()
            val extra = java.nio.ByteBuffer.allocate(minExtra + pad)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putShort(0xd935.toShort()).putShort((2 + pad).toShort()).putShort(alignment.toShort())
                .array()
            val entry = ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = body.size.toLong()
                compressedSize = body.size.toLong()
                crc = CRC32().apply { update(body) }.value
                this.extra = extra
            }
            zip.putNextEntry(entry); zip.write(body); zip.closeEntry()
        }
        return file
    }
}
```

- [ ] **Step 2: Run and verify inspector is missing**

Run: `.\gradlew.bat :harden-release-core:test --tests "*.ZipAlignmentInspectorTest"`

Expected: compilation FAIL.

- [ ] **Step 3: Implement a ZIP32 central-directory reader**

```kotlin
package com.apkharden.release.apk

import java.io.File
import java.io.RandomAccessFile

@kotlinx.serialization.Serializable
data class NativeZipEntry(
    val name: String,
    val compressionMethod: Int,
    val dataOffset: Long,
    val aligned16k: Boolean,
)

object ZipAlignmentInspector {
    private const val EOCD = 0x06054b50L
    private const val CENTRAL = 0x02014b50L
    private const val LOCAL = 0x04034b50L
    private const val STORED = 0

    fun inspect(apk: File): List<NativeZipEntry> = RandomAccessFile(apk, "r").use { raf ->
        val eocd = findEocd(raf)
        val count = u16(raf, eocd + 10)
        val centralOffset = u32(raf, eocd + 16)
        require(count != 0xffff && centralOffset != 0xffffffffL) {
            "ZIP64 APK is not supported by the release inspector"
        }
        val result = mutableListOf<NativeZipEntry>()
        var cursor = centralOffset
        repeat(count) {
            require(u32(raf, cursor) == CENTRAL) { "Invalid central directory at $cursor" }
            val method = u16(raf, cursor + 10)
            val nameLength = u16(raf, cursor + 28)
            val extraLength = u16(raf, cursor + 30)
            val commentLength = u16(raf, cursor + 32)
            val localOffset = u32(raf, cursor + 42)
            val name = text(raf, cursor + 46, nameLength)
            if (name.startsWith("lib/") && name.endsWith(".so")) {
                require(u32(raf, localOffset) == LOCAL) { "Invalid local header for $name" }
                val localNameLength = u16(raf, localOffset + 26)
                val localExtraLength = u16(raf, localOffset + 28)
                val dataOffset = localOffset + 30 + localNameLength + localExtraLength
                result += NativeZipEntry(
                    name, method, dataOffset,
                    aligned16k = method != STORED || dataOffset % 16384L == 0L,
                )
            }
            cursor += 46L + nameLength + extraLength + commentLength
        }
        result
    }

    private fun findEocd(raf: RandomAccessFile): Long {
        val start = (raf.length() - 22).coerceAtLeast(0)
        val minimum = (raf.length() - 65557).coerceAtLeast(0)
        for (offset in start downTo minimum) {
            if (u32(raf, offset) == EOCD) return offset
        }
        throw IllegalArgumentException("ZIP end-of-central-directory not found")
    }

    private fun u16(raf: RandomAccessFile, offset: Long): Int {
        raf.seek(offset)
        return raf.readUnsignedByte() or (raf.readUnsignedByte() shl 8)
    }

    private fun u32(raf: RandomAccessFile, offset: Long): Long {
        raf.seek(offset)
        return (raf.readUnsignedByte().toLong() or
            (raf.readUnsignedByte().toLong() shl 8) or
            (raf.readUnsignedByte().toLong() shl 16) or
            (raf.readUnsignedByte().toLong() shl 24)) and 0xffffffffL
    }

    private fun text(raf: RandomAccessFile, offset: Long, length: Int): String {
        val bytes = ByteArray(length)
        raf.seek(offset); raf.readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }
}
```

- [ ] **Step 4: Run tests and commit**

Run: `.\gradlew.bat :harden-release-core:test --tests "*.ZipAlignmentInspectorTest"`

Expected: PASS, two tests.

```powershell
git add harden-release-core/src/main/kotlin/com/apkharden/release/apk/ZipAlignmentInspector.kt harden-release-core/src/test/kotlin/com/apkharden/release/apk/ZipAlignmentInspectorTest.kt
git commit -m "feat(release): verify 16KB APK alignment"
```

---

### Task 8: Inspect ELF LOAD segment compatibility

**Files:**
- Create: `harden-release-core/src/main/kotlin/com/apkharden/release/apk/ElfAlignmentInspector.kt`
- Create: `harden-release-core/src/test/kotlin/com/apkharden/release/apk/ElfAlignmentInspectorTest.kt`

- [ ] **Step 1: Write failing ELF32/ELF64 tests**

```kotlin
package com.apkharden.release.apk

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ElfAlignmentInspectorTest {
    @Test fun `ELF64 LOAD with 16KB alignment passes`() {
        assertTrue(ElfAlignmentInspector.inspect(elf64(16384)).compatible16k)
    }

    @Test fun `ELF64 LOAD with 4KB alignment fails`() {
        assertFalse(ElfAlignmentInspector.inspect(elf64(4096)).compatible16k)
    }

    private fun elf64(alignment: Long): ByteArray {
        val data = ByteBuffer.allocate(64 + 56).order(ByteOrder.LITTLE_ENDIAN)
        data.put(0x7f.toByte()); data.put('E'.code.toByte()); data.put('L'.code.toByte()); data.put('F'.code.toByte())
        data.put(2.toByte()); data.put(1.toByte())
        data.position(32); data.putLong(64)
        data.position(54); data.putShort(56); data.putShort(1)
        data.position(64); data.putInt(1)
        data.position(72); data.putLong(0); data.putLong(0); data.putLong(0)
        data.position(112); data.putLong(alignment)
        return data.array()
    }
}
```

- [ ] **Step 2: Run and verify inspector is missing**

Run: `.\gradlew.bat :harden-release-core:test --tests "*.ElfAlignmentInspectorTest"`

Expected: compilation FAIL.

- [ ] **Step 3: Implement little-endian ELF32/ELF64 parsing**

```kotlin
package com.apkharden.release.apk

import java.nio.ByteBuffer
import java.nio.ByteOrder

@kotlinx.serialization.Serializable
data class ElfLoadSegment(
    val fileOffset: Long,
    val virtualAddress: Long,
    val alignment: Long,
)

@kotlinx.serialization.Serializable
data class ElfAlignmentResult(
    val elfClass: Int,
    val loadSegments: List<ElfLoadSegment>,
    val compatible16k: Boolean,
) {
    val loadAlignments: List<Long> get() = loadSegments.map { it.alignment }
}

object ElfAlignmentInspector {
    private const val PT_LOAD = 1

    fun inspect(bytes: ByteArray): ElfAlignmentResult {
        require(bytes.size >= 52) { "ELF file is too small" }
        require(bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() && bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()) {
            "Native library has invalid ELF magic"
        }
        val elfClass = bytes[4].toInt()
        require(bytes[5].toInt() == 1) { "Only little-endian ELF is supported" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val (programOffset, entrySize, count, alignOffset) = when (elfClass) {
            1 -> Quad(buffer.getInt(28).toLong() and 0xffffffffL, buffer.getShort(42).toInt() and 0xffff, buffer.getShort(44).toInt() and 0xffff, 28)
            2 -> Quad(buffer.getLong(32), buffer.getShort(54).toInt() and 0xffff, buffer.getShort(56).toInt() and 0xffff, 48)
            else -> throw IllegalArgumentException("Unsupported ELF class: $elfClass")
        }
        val segments = buildList {
            repeat(count) { index ->
                val base = Math.toIntExact(programOffset + index.toLong() * entrySize)
                require(base >= 0 && base + entrySize <= bytes.size) { "ELF program header is truncated" }
                if (buffer.getInt(base) == PT_LOAD) {
                    val fileOffset = if (elfClass == 1) buffer.getInt(base + 4).toLong() and 0xffffffffL else buffer.getLong(base + 8)
                    val virtualAddress = if (elfClass == 1) buffer.getInt(base + 8).toLong() and 0xffffffffL else buffer.getLong(base + 16)
                    val alignment = if (elfClass == 1) buffer.getInt(base + alignOffset).toLong() and 0xffffffffL else buffer.getLong(base + alignOffset)
                    add(ElfLoadSegment(fileOffset, virtualAddress, alignment))
                }
            }
        }
        require(segments.isNotEmpty()) { "ELF has no PT_LOAD segment" }
        val compatible = segments.all {
            it.alignment >= 16384L && (it.virtualAddress - it.fileOffset) % 16384L == 0L
        }
        return ElfAlignmentResult(elfClass, segments, compatible)
    }

    private data class Quad(val offset: Long, val size: Int, val count: Int, val alignOffset: Int)
}
```

- [ ] **Step 4: Add APK-level native inspection**

Add to `ElfAlignmentInspector`:

```kotlin
fun inspectApk(apk: java.io.File): Map<String, ElfAlignmentResult> =
    java.util.zip.ZipFile(apk).use { zip ->
        zip.entries().asSequence()
            .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
            .associate { entry ->
                entry.name to inspect(zip.getInputStream(entry).use { it.readBytes() })
            }
    }
```

- [ ] **Step 5: Run tests and commit**

Run: `.\gradlew.bat :harden-release-core:test --tests "*.ElfAlignmentInspectorTest"`

Expected: PASS, two tests.

```powershell
git add harden-release-core/src/main/kotlin/com/apkharden/release/apk/ElfAlignmentInspector.kt harden-release-core/src/test/kotlin/com/apkharden/release/apk/ElfAlignmentInspectorTest.kt
git commit -m "feat(release): verify ELF 16KB compatibility"
```

---
### Task 9: Orchestrate static analysis and native findings

**Files:**
- Create: `harden-release-core/src/main/kotlin/com/apkharden/release/gate/ReleaseAnalyzer.kt`
- Create: `harden-release-core/src/test/kotlin/com/apkharden/release/gate/ReleaseAnalyzerTest.kt`

- [ ] **Step 1: Write a failing end-to-end analyzer test**

```kotlin
package com.apkharden.release.gate

import com.apkharden.release.fixture.TestApkFactory
import com.apkharden.release.model.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ReleaseAnalyzerTest {
    @TempDir lateinit var temp: File

    @Test fun `valid fixture reaches static verified`() {
        val online = TestApkFactory.sign(
            TestApkFactory.createUnsigned(temp, "com.example.app", 10),
            File(temp, "online.apk"),
        )
        val candidate = TestApkFactory.sign(
            TestApkFactory.createUnsigned(temp, "com.example.app", 11),
            File(temp, "candidate.apk"),
        )
        val metadata = TestApkFactory.metadata(
            File(temp, "metadata.json"), "com.example.app", 11, emptySet()
        )
        val assessment = ReleaseAnalyzer.analyze(
            ReleaseRequest(
                online, candidate, metadata,
                KeystoreRequest(
                    File("../src/test/resources/test.jks"),
                    "123456".toCharArray(), "test", "123456".toCharArray(),
                ),
            )
        )
        assertEquals(ReleaseStatus.STATIC_VERIFIED, assessment.status)
    }
}
```

Add these imports and helper to `TestApkFactory`:

```kotlin
import com.apkharden.release.metadata.HardenMetadata
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun metadata(
    output: File,
    applicationId: String,
    versionCode: Long,
    abis: Set<String>,
): File {
    val cert = KeystoreReader.load(keystoreRequest()).identity.certificateSha256
    val value = HardenMetadata(
        schemaVersion = 1,
        pluginVersion = "1.0.0",
        runtimeVersion = "1.0.0",
        variantName = "fixture",
        applicationId = applicationId,
        versionCode = versionCode,
        minSdk = 23,
        targetSdk = 36,
        debuggable = false,
        r8Enabled = false,
        abis = abis,
        expectedCertificateSha256 = cert,
        buildId = "fixture-build",
    )
    output.writeText(Json.encodeToString(value))
    return output
}
```

- [ ] **Step 2: Run and verify analyzer is missing**

Run: `.\gradlew.bat :harden-release-core:test --tests "*.ReleaseAnalyzerTest"`

Expected: compilation FAIL.

- [ ] **Step 3: Implement the analyzer**

```kotlin
package com.apkharden.release.gate

import com.apkharden.release.apk.*
import com.apkharden.release.crypto.KeystoreReader
import com.apkharden.release.metadata.HardenMetadataReader
import com.apkharden.release.model.*

object ReleaseAnalyzer {
    fun analyze(request: ReleaseRequest): ReleaseAssessment {
        val online = ApkIdentityReader.read(request.onlineApk)
        val candidate = ApkIdentityReader.read(request.candidateApk)
        val metadata = HardenMetadataReader.read(request.metadataFile)
        val keystore = KeystoreReader.load(request.keystore)
        val findings = ReleaseGateEvaluator.evaluate(
            online, candidate, metadata, keystore.identity
        ).toMutableList()

        ZipAlignmentInspector.inspect(request.candidateApk)
            .filterNot { it.aligned16k }
            .forEach {
                findings += ReleaseFinding(
                    "NATIVE_ZIP_NOT_16K_ALIGNED",
                    FindingLevel.BLOCKER,
                    "Uncompressed native library is not 16KB aligned",
                    mapOf("entry" to it.name, "offset" to it.dataOffset.toString()),
                )
            }

        runCatching { ElfAlignmentInspector.inspectApk(request.candidateApk) }
            .onSuccess { results ->
                results.filterValues { !it.compatible16k }.forEach { (name, result) ->
                    findings += ReleaseFinding(
                        "ELF_NOT_16K_COMPATIBLE",
                        FindingLevel.BLOCKER,
                        "Native library LOAD segments are not 16KB compatible",
                        mapOf("entry" to name, "alignments" to result.loadAlignments.joinToString()),
                    )
                }
            }
            .onFailure { error ->
                if (candidate.abis.isNotEmpty()) {
                    findings += ReleaseFinding(
                        "ELF_INSPECTION_FAILED", FindingLevel.BLOCKER,
                        error.message ?: "ELF inspection failed",
                    )
                }
            }

        return ReleaseAssessment(
            findings.sortedWith(compareBy({ it.level.ordinal }, { it.code })),
            request.approvedFindingCodes,
            online,
            candidate,
            keystore.identity,
        )
    }
}
```

- [ ] **Step 4: Run analyzer and all module tests**

Run:

```powershell
.\gradlew.bat :harden-release-core:test --tests "*.ReleaseAnalyzerTest"
.\gradlew.bat :harden-release-core:test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add harden-release-core/src/main/kotlin/com/apkharden/release/gate/ReleaseAnalyzer.kt harden-release-core/src/test/kotlin/com/apkharden/release
git commit -m "feat(release): analyze static release qualification"
```

---

### Task 10: Sign candidates and verify content preservation

**Files:**
- Create: `harden-release-core/src/main/kotlin/com/apkharden/release/signing/ReleaseSigner.kt`
- Create: `harden-release-core/src/test/kotlin/com/apkharden/release/signing/ReleaseSignerTest.kt`

- [ ] **Step 1: Write a failing signing test**

```kotlin
package com.apkharden.release.signing

import com.apkharden.release.apk.ApkSignatureReader
import com.apkharden.release.fixture.TestApkFactory
import com.apkharden.release.model.KeystoreRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile

class ReleaseSignerTest {
    @TempDir lateinit var temp: File

    @Test fun `signing preserves non-signature entry bytes and creates V1 V2 V3`() {
        val input = TestApkFactory.createUnsigned(temp, "com.example.app", 11)
        val before = content(input)
        val output = File(temp, "release.apk")
        ReleaseSigner.sign(input, output, TestApkFactory.keystoreRequest())
        assertEquals(before, content(output))
        val signature = ApkSignatureReader.read(output)
        assertTrue(signature.verified && signature.v1 && signature.v2 && signature.v3)
    }

    private fun content(apk: File): Map<String, List<Byte>> = ZipFile(apk).use { zip ->
        zip.entries().asSequence()
            .filterNot { it.name.startsWith("META-INF/") }
            .associate { it.name to zip.getInputStream(it).use { stream -> stream.readBytes().toList() } }
    }
}
```

- [ ] **Step 2: Run and verify signer is missing**

Run: `.\gradlew.bat :harden-release-core:test --tests "*.ReleaseSignerTest"`

Expected: compilation FAIL.

- [ ] **Step 3: Implement signing and post-sign checks**

```kotlin
package com.apkharden.release.signing

import com.android.apksig.ApkSigner
import com.apkharden.release.apk.ApkSignatureReader
import com.apkharden.release.apk.ZipAlignmentInspector
import com.apkharden.release.crypto.KeystoreReader
import com.apkharden.release.model.KeystoreRequest
import java.io.File

object ReleaseSigner {
    fun sign(input: File, output: File, request: KeystoreRequest) {
        require(input.isFile) { "Candidate APK not found: $input" }
        require(input.canonicalFile != output.canonicalFile) { "Input and output APK must differ" }
        val key = KeystoreReader.load(request)
        val config = ApkSigner.SignerConfig.Builder(
            "RELEASE", key.privateKey, key.certificateChain
        ).build()
        ApkSigner.Builder(listOf(config))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()

        val result = ApkSignatureReader.read(output)
        check(result.verified && result.v1 && result.v2 && result.v3) {
            "Signed APK failed V1/V2/V3 verification"
        }
        check(result.signerSha256 == setOf(key.identity.certificateSha256)) {
            "Signed APK certificate differs from keystore"
        }
        check(ZipAlignmentInspector.inspect(output).all { it.aligned16k }) {
            "Signing broke 16KB native ZIP alignment"
        }
    }
}
```

Add to `TestApkFactory`:

```kotlin
fun keystoreRequest() = KeystoreRequest(
    testKey, "123456".toCharArray(), "test", "123456".toCharArray()
)
```

- [ ] **Step 4: Run tests and commit**

Run:

```powershell
.\gradlew.bat :harden-release-core:test --tests "*.ReleaseSignerTest"
.\gradlew.bat :harden-release-core:test
```

Expected: PASS.

```powershell
git add harden-release-core/src/main/kotlin/com/apkharden/release/signing harden-release-core/src/test/kotlin/com/apkharden/release
git commit -m "feat(release): sign verified release APKs"
```

---

### Task 11: Emit JSON reports and add a headless release-check CLI

**Files:**
- Create: `harden-release-core/src/main/kotlin/com/apkharden/release/report/ReleaseReportWriter.kt`
- Create: `harden-release-core/src/main/kotlin/com/apkharden/release/cli/ReleaseCheckCli.kt`
- Create: `harden-release-core/src/test/kotlin/com/apkharden/release/report/ReleaseReportWriterTest.kt`
- Create: `harden-release-core/src/test/kotlin/com/apkharden/release/cli/ReleaseCheckCliTest.kt`
- Modify: `harden-release-core/build.gradle.kts`
- Modify: root `build.gradle.kts`

- [ ] **Step 1: Write failing report and CLI exit-code tests**

```kotlin
package com.apkharden.release.report

import com.apkharden.release.model.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ReleaseReportWriterTest {
    @TempDir lateinit var temp: File

    @Test fun `report contains status and stable finding code`() {
        val output = File(temp, "report.json")
        ReleaseReportWriter.write(
            ReleaseAssessment(
                listOf(ReleaseFinding("PACKAGE_MISMATCH", FindingLevel.BLOCKER, "diff"))
            ),
            output,
        )
        val text = output.readText()
        assertTrue(text.contains("NOT_QUALIFIED"))
        assertTrue(text.contains("PACKAGE_MISMATCH"))
    }
}
```

```kotlin
package com.apkharden.release.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReleaseCheckCliTest {
    @org.junit.jupiter.api.io.TempDir lateinit var temp: java.io.File

    @Test fun `missing arguments return usage error without reading passwords`() {
        assertEquals(2, ReleaseCheckCli.run(emptyArray(), emptyMap()))
    }

    @Test fun `valid fixtures return zero and write static verified report`() {
        val online = com.apkharden.release.fixture.TestApkFactory.sign(
            com.apkharden.release.fixture.TestApkFactory.createUnsigned(temp, "com.example.app", 10),
            java.io.File(temp, "online.apk"),
        )
        val candidate = com.apkharden.release.fixture.TestApkFactory.sign(
            com.apkharden.release.fixture.TestApkFactory.createUnsigned(temp, "com.example.app", 11),
            java.io.File(temp, "candidate.apk"),
        )
        val metadata = com.apkharden.release.fixture.TestApkFactory.metadata(
            java.io.File(temp, "metadata.json"), "com.example.app", 11, emptySet()
        )
        val report = java.io.File(temp, "report.json")
        val code = ReleaseCheckCli.run(
            arrayOf(
                "--online", online.path,
                "--candidate", candidate.path,
                "--metadata", metadata.path,
                "--keystore", "../src/test/resources/test.jks",
                "--alias", "test",
                "--report", report.path,
            ),
            mapOf("APK_HARDEN_STORE_PASS" to "123456", "APK_HARDEN_KEY_PASS" to "123456"),
        )
        assertEquals(0, code)
        org.junit.jupiter.api.Assertions.assertTrue(report.readText().contains("STATIC_VERIFIED"))
    }
}
```

- [ ] **Step 2: Run and verify missing report/CLI**

Run:

```powershell
.\gradlew.bat :harden-release-core:test --tests "*.ReleaseReportWriterTest" --tests "*.ReleaseCheckCliTest"
```

Expected: compilation FAIL.

- [ ] **Step 3: Implement JSON report writing**

```kotlin
package com.apkharden.release.report

import com.apkharden.release.model.ReleaseAssessment
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object ReleaseReportWriter {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun write(assessment: ReleaseAssessment, output: File) {
        output.parentFile?.mkdirs()
        output.writeText(json.encodeToString(assessment))
    }
}
```

- [ ] **Step 4: Implement a password-safe CLI**

```kotlin
package com.apkharden.release.cli

import com.apkharden.release.gate.ReleaseAnalyzer
import com.apkharden.release.model.*
import com.apkharden.release.report.ReleaseReportWriter
import java.io.File

object ReleaseCheckCli {
    fun run(args: Array<String>, env: Map<String, String> = System.getenv()): Int {
        val values = args.toList().chunked(2)
            .filter { it.size == 2 && it[0].startsWith("--") }
            .associate { it[0].removePrefix("--") to it[1] }
        val required = listOf("online", "candidate", "metadata", "keystore", "alias", "report")
        if (required.any { values[it].isNullOrBlank() }) {
            System.err.println("Usage: --online old.apk --candidate new.apk --metadata metadata.json --keystore release.jks --alias alias --report report.json")
            return 2
        }
        val storePass = env["APK_HARDEN_STORE_PASS"] ?: return 3
        val keyPass = env["APK_HARDEN_KEY_PASS"] ?: return 3
        val assessment = ReleaseAnalyzer.analyze(
            ReleaseRequest(
                File(values.getValue("online")),
                File(values.getValue("candidate")),
                File(values.getValue("metadata")),
                KeystoreRequest(
                    File(values.getValue("keystore")),
                    storePass.toCharArray(), values.getValue("alias"), keyPass.toCharArray(),
                ),
            )
        )
        ReleaseReportWriter.write(assessment, File(values.getValue("report")))
        println("status=${assessment.status}")
        assessment.findings.forEach { println("${it.level}:${it.code}:${it.message}") }
        return if (assessment.status == ReleaseStatus.STATIC_VERIFIED) 0 else 1
    }
}

fun main(args: Array<String>) {
    kotlin.system.exitProcess(ReleaseCheckCli.run(args))
}
```

- [ ] **Step 5: Register module and root CLI tasks**

Add to `harden-release-core/build.gradle.kts`:

```kotlin
val releaseCheck by tasks.registering(JavaExec::class) {
    group = "apkharden"
    mainClass.set("com.apkharden.release.cli.ReleaseCheckCliKt")
    classpath = sourceSets.main.get().runtimeClasspath
}
```

Add to root `build.gradle.kts`:

```kotlin
tasks.register("releaseCheck") {
    group = "apkharden"
    dependsOn(":harden-release-core:releaseCheck")
}
```

- [ ] **Step 6: Run focused and full clean verification**

Run:

```powershell
.\gradlew.bat :harden-release-core:test --tests "*.ReleaseReportWriterTest" --tests "*.ReleaseCheckCliTest"
.\gradlew.bat clean test :harden-release-core:test
```

Expected: all root and release-core tests pass from clean outputs.

- [ ] **Step 7: Run valid and invalid CLI integration tests**

Run:

```powershell
.\gradlew.bat :harden-release-core:test --tests "*.ReleaseCheckCliTest"
.\gradlew.bat :harden-release-core:test --tests "*.ReleaseGateEvaluatorTest"
```

Expected: the valid fixture returns exit 0 with `STATIC_VERIFIED`; the gate suite proves an equal versionCode produces `VERSION_NOT_INCREMENTED` and `NOT_QUALIFIED`.

- [ ] **Step 8: Commit the phase-one public entry point**

```powershell
git add build.gradle.kts harden-release-core
git commit -m "feat(release): add static release-check CLI"
```

---

## Phase 1 Final Verification

- [ ] Run clean tests:

```powershell
.\gradlew.bat clean test :harden-release-core:test
```

Expected: zero failed tests.

- [ ] Run `git diff --check`:

```powershell
git diff --check
```

Expected: no output, exit 0.

- [ ] Confirm current experimental hardening tests remain green and no existing source file moved.

- [ ] Confirm every phase-one blocker has a stable test assertion:

```text
ONLINE_APK_UNSIGNED
ONLINE_SIGNER_COUNT_UNSUPPORTED
SIGNING_LINEAGE_UNSUPPORTED
PACKAGE_MISMATCH
VERSION_NOT_INCREMENTED
DEBUGGABLE_CANDIDATE
TEST_ONLY_CANDIDATE
MIN_SDK_UNSUPPORTED
SPLIT_APK_UNSUPPORTED
ONLINE_CERT_KEYSTORE_MISMATCH
CANDIDATE_CERT_KEYSTORE_MISMATCH
METADATA_PACKAGE_MISMATCH
METADATA_VERSION_MISMATCH
METADATA_MIN_SDK_MISMATCH
METADATA_TARGET_SDK_MISMATCH
METADATA_DEBUGGABLE_MISMATCH
METADATA_ABI_MISMATCH
METADATA_CERT_KEYSTORE_MISMATCH
NATIVE_ZIP_NOT_16K_ALIGNED
ELF_NOT_16K_COMPATIBLE
```

- [ ] Update the roadmap Phase 1 status only after all commands pass.
