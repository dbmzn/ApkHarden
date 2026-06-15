# ApkHarden Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a self-contained Compose Desktop tool that hardens an Android APK with first-generation DEX shell protection (whole-dex encryption + in-memory load), anti-repackaging (signature check), and basic anti-debug — matching 360's free basic tier.

**Architecture:** A JVM/Kotlin Compose Desktop "packager" rips a target APK apart, AES-encrypts its `classes*.dex` into assets, swaps in a prebuilt Java `shell.dex` as the new `classes.dex`, rewrites the binary `AndroidManifest.xml` so the app entry becomes `ProxyApplication` (with meta-data carrying the original app class, expected signature hash, and dex count), repackages, and re-signs with apksig (V1/V2/V3). At runtime the shell verifies signature + anti-debug, decrypts the dexes, loads them via `InMemoryDexClassLoader`, reflectively swaps `LoadedApk.mClassLoader`, then hands control to the original `Application`.

**Tech Stack:** Kotlin 1.9 + Compose Desktop (packager UI), Java (shell, compiled with `javac` + `d8`), `com.android.tools.build:apksig` (signing), `io.github.reandroid:ARSCLib` (binary manifest editing), JDK `javax.crypto` (AES), JUnit 5 (tests).

**minSdk:** 26+ (uses `InMemoryDexClassLoader`).

---

## Conventions locked for this plan

- AES: `AES/CBC/PKCS5Padding`, 16-byte key, random 16-byte IV **prepended** to each ciphertext.
- AES key (shared by packager + shell), as raw bytes:
  `{0x31,0x6B,0x9F,0x24,0xC8,0x0A,0x55,0xE3,0x77,0x12,0xAB,0x4D,0x90,0x6E,0x88,0x1F}`
  In the shell this is stored XOR'd with `0x5A` and de-XOR'd at runtime (light obfuscation).
- Encrypted dex assets live at `assets/d/0`, `assets/d/1`, … (one per original `classes*.dex`).
- New manifest meta-data keys (string values on `<application>`):
  - `com.apkharden.APP_NAME` → original application class FQN (empty if app had none)
  - `com.apkharden.SIG_HASH` → expected signing cert SHA-256, lowercase hex
  - `com.apkharden.DEX_COUNT` → integer count of encrypted dex files
- Shell entry class: `com.apkharden.shell.ProxyApplication`.
- Package roots: packager `com.apkharden.packager`, shell `com.apkharden.shell`.

---

## File Structure

```
ApkHarden/
├── settings.gradle.kts
├── build.gradle.kts
├── gradlew / gradlew.bat / gradle/wrapper/...
├── src/main/kotlin/com/apkharden/packager/
│   ├── Main.kt                       # Compose entry
│   ├── ui/HardenScreen.kt            # GUI
│   └── core/
│       ├── Constants.kt              # shared keys/paths/AES key
│       ├── DexEncryptor.kt           # AES encrypt
│       ├── KeystoreUtil.kt           # load key/certs + expected sig hash
│       ├── ApkReader.kt              # read zip entries / dex / manifest bytes
│       ├── ManifestPatcher.kt        # ARSCLib manifest edit
│       ├── ApkRepackager.kt          # assemble new (unsigned) apk zip
│       ├── ApkSignerWrapper.kt       # apksig sign + verify
│       └── HardenPipeline.kt         # orchestrates the whole flow
├── src/main/resources/shell.dex      # prebuilt, embedded (produced by scripts/build-shell)
├── src/test/kotlin/com/apkharden/packager/core/   # JUnit tests
├── src/test/resources/test.jks       # test keystore (generated via keytool)
├── shell/com/apkharden/shell/        # Java sources for the shell
│   ├── Constants.java
│   ├── DexDecryptor.java
│   ├── AntiDebug.java
│   ├── AntiTamper.java
│   └── ProxyApplication.java
├── scripts/build-shell.ps1           # javac + d8 → src/main/resources/shell.dex
└── samples/                          # demo apps for e2e (built separately)
```

---

## Task 0: Project scaffolding

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `src/main/kotlin/com/apkharden/packager/Main.kt`
- Create: `.gitignore`

- [ ] **Step 1: Create `settings.gradle.kts`**

```kotlin
rootProject.name = "ApkHarden"
```

- [ ] **Step 2: Create `build.gradle.kts`**

```kotlin
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "1.9.23"
    id("org.jetbrains.compose") version "1.6.2"
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("com.android.tools.build:apksig:8.3.2")
    implementation("io.github.reandroid:ARSCLib:1.3.4")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test { useJUnitPlatform() }

kotlin { jvmToolchain(17) }

compose.desktop {
    application {
        mainClass = "com.apkharden.packager.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "ApkHarden"
            packageVersion = "1.0.0"
        }
    }
}
```

- [ ] **Step 3: Create `.gitignore`**

```
.gradle/
build/
local.properties
*.iml
.idea/
```

- [ ] **Step 4: Create a minimal `Main.kt` so the project compiles**

```kotlin
package com.apkharden.packager

import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.ui.window.application
import androidx.compose.ui.window.Window

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "ApkHarden") {
        MaterialTheme { Text("ApkHarden") }
    }
}
```

- [ ] **Step 5: Generate the Gradle wrapper**

Run: `gradle wrapper --gradle-version 8.7`
(If `gradle` is not on PATH, copy a wrapper from another project under `C:\Users\huqiang\StudioProjects` and set `distributionUrl` to `gradle-8.7-bin.zip`.)

- [ ] **Step 6: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "chore: scaffold ApkHarden Compose Desktop project"
```

---

## Task 1: Constants + DexEncryptor (TDD)

**Files:**
- Create: `src/main/kotlin/com/apkharden/packager/core/Constants.kt`
- Create: `src/main/kotlin/com/apkharden/packager/core/DexEncryptor.kt`
- Test: `src/test/kotlin/com/apkharden/packager/core/DexEncryptorTest.kt`

- [ ] **Step 1: Create `Constants.kt`**

```kotlin
package com.apkharden.packager.core

object Constants {
    const val PROXY_APPLICATION = "com.apkharden.shell.ProxyApplication"

    const val META_APP_NAME = "com.apkharden.APP_NAME"
    const val META_SIG_HASH = "com.apkharden.SIG_HASH"
    const val META_DEX_COUNT = "com.apkharden.DEX_COUNT"

    const val ENCRYPTED_DEX_DIR = "d"            // assets/d/<index>
    fun encryptedDexEntry(index: Int) = "assets/$ENCRYPTED_DEX_DIR/$index"

    // 16-byte AES key shared with the shell (shell stores it XOR'd with 0x5A).
    val AES_KEY = byteArrayOf(
        0x31, 0x6B, 0x9F.toByte(), 0x24, 0xC8.toByte(), 0x0A, 0x55, 0xE3.toByte(),
        0x77, 0x12, 0xAB.toByte(), 0x4D, 0x90.toByte(), 0x6E, 0x88.toByte(), 0x1F
    )
}
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.apkharden.packager.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DexEncryptorTest {
    // Mirror of the shell-side decrypt, to prove round-trip compatibility.
    private fun decrypt(blob: ByteArray): ByteArray {
        val iv = blob.copyOfRange(0, 16)
        val body = blob.copyOfRange(16, blob.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(Constants.AES_KEY, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(body)
    }

    @Test
    fun `encrypt then decrypt returns original`() {
        val original = ByteArray(5000) { (it % 256).toByte() }
        val blob = DexEncryptor.encrypt(original)
        assertFalse(blob.copyOfRange(16, blob.size).contentEquals(original)) // body differs from plaintext
        assertArrayEquals(original, decrypt(blob))
    }

    @Test
    fun `two encryptions of same input differ (random IV)`() {
        val original = "hello dex".toByteArray()
        val a = DexEncryptor.encrypt(original)
        val b = DexEncryptor.encrypt(original)
        assertFalse(a.contentEquals(b))
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests "*DexEncryptorTest*"`
Expected: FAIL — `DexEncryptor` unresolved.

- [ ] **Step 4: Implement `DexEncryptor.kt`**

```kotlin
package com.apkharden.packager.core

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object DexEncryptor {
    private val rng = SecureRandom()

    /** Returns [16-byte IV] + AES/CBC/PKCS5 ciphertext. */
    fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(16).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(Constants.AES_KEY, "AES"), IvParameterSpec(iv))
        return iv + cipher.doFinal(plain)
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "*DexEncryptorTest*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: AES dex encryptor + shared constants"
```

---

## Task 2: KeystoreUtil (TDD)

Loads the user's keystore and computes the expected signing-cert SHA-256 (what the shell will compare at runtime).

**Files:**
- Create: `src/main/kotlin/com/apkharden/packager/core/KeystoreUtil.kt`
- Test: `src/test/kotlin/com/apkharden/packager/core/KeystoreUtilTest.kt`
- Create: `src/test/resources/test.jks` (generated below)

- [ ] **Step 1: Generate the test keystore**

Run:
```
keytool -genkeypair -keystore src/test/resources/test.jks -storetype JKS -alias test -keyalg RSA -keysize 2048 -validity 10000 -storepass 123456 -keypass 123456 -dname "CN=ApkHarden Test"
```
Expected: `src/test/resources/test.jks` created.

- [ ] **Step 2: Write the failing test**

```kotlin
package com.apkharden.packager.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class KeystoreUtilTest {
    private val ks = File("src/test/resources/test.jks")

    @Test
    fun `loads private key and cert chain`() {
        val creds = KeystoreUtil.load(ks, "123456", "test", "123456")
        assertTrue(creds.certificates.isNotEmpty())
        assertEquals("RSA", creds.privateKey.algorithm)
    }

    @Test
    fun `expected signature hash is 64 lowercase hex chars and stable`() {
        val creds = KeystoreUtil.load(ks, "123456", "test", "123456")
        val hash = KeystoreUtil.expectedSigHash(creds)
        assertTrue(hash.matches(Regex("[0-9a-f]{64}")))
        // stable across calls
        assertEquals(hash, KeystoreUtil.expectedSigHash(KeystoreUtil.load(ks, "123456", "test", "123456")))
    }

    @Test
    fun `wrong store password throws`() {
        try {
            KeystoreUtil.load(ks, "wrong", "test", "123456")
            throw AssertionError("expected failure")
        } catch (e: Exception) { /* expected */ }
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests "*KeystoreUtilTest*"`
Expected: FAIL — `KeystoreUtil` unresolved.

- [ ] **Step 4: Implement `KeystoreUtil.kt`**

```kotlin
package com.apkharden.packager.core

import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate

class KeystoreCredentials(
    val privateKey: PrivateKey,
    val certificates: List<X509Certificate>,
)

object KeystoreUtil {
    fun load(file: File, storePass: String, alias: String, keyPass: String): KeystoreCredentials {
        // JKS first, fall back to PKCS12 for .jks files that are actually PKCS12.
        val ks = try {
            KeyStore.getInstance("JKS").also { it.load(file.inputStream(), storePass.toCharArray()) }
        } catch (e: Exception) {
            KeyStore.getInstance("PKCS12").also { it.load(file.inputStream(), storePass.toCharArray()) }
        }
        val key = ks.getKey(alias, keyPass.toCharArray()) as? PrivateKey
            ?: throw IllegalArgumentException("No private key for alias '$alias'")
        val chain = (ks.getCertificateChain(alias)
            ?: throw IllegalArgumentException("No certificate chain for alias '$alias'"))
            .map { it as X509Certificate }
        return KeystoreCredentials(key, chain)
    }

    /** SHA-256 of the leaf signing certificate DER (matches PackageManager Signature bytes). */
    fun expectedSigHash(creds: KeystoreCredentials): String {
        val der = creds.certificates.first().encoded
        val digest = MessageDigest.getInstance("SHA-256").digest(der)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "*KeystoreUtilTest*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: keystore loader + expected signature hash"
```

---

## Task 3: The shell (Java) + build script

The shell is plain Java compiled to a single `shell.dex`. It cannot be unit-tested on the JVM (needs Android); it is verified by compiling, producing `shell.dex`, and later by the on-device e2e in Task 10.

**Files:**
- Create: `shell/com/apkharden/shell/Constants.java`
- Create: `shell/com/apkharden/shell/DexDecryptor.java`
- Create: `shell/com/apkharden/shell/AntiDebug.java`
- Create: `shell/com/apkharden/shell/AntiTamper.java`
- Create: `shell/com/apkharden/shell/ProxyApplication.java`
- Create: `scripts/build-shell.ps1`

- [ ] **Step 1: `Constants.java`** (keys must match the packager `Constants.kt`)

```java
package com.apkharden.shell;

public final class Constants {
    public static final String META_APP_NAME = "com.apkharden.APP_NAME";
    public static final String META_SIG_HASH = "com.apkharden.SIG_HASH";
    public static final String META_DEX_COUNT = "com.apkharden.DEX_COUNT";
    public static final String ENC_DIR = "d"; // assets/d/<i>

    // Same 16 bytes as packager Constants.AES_KEY, XOR'd with 0x5A.
    private static final byte[] OBF = {
        0x6B, 0x31, (byte)0xC5, 0x7E, (byte)0x92, 0x50, 0x0F, (byte)0xB9,
        0x2D, 0x48, (byte)0xF1, 0x17, (byte)0xCA, 0x34, (byte)0xD2, 0x45
    };

    public static byte[] aesKey() {
        byte[] k = new byte[OBF.length];
        for (int i = 0; i < OBF.length; i++) k[i] = (byte) (OBF[i] ^ 0x5A);
        return k;
    }

    private Constants() {}
}
```

> Note: each OBF byte = corresponding AES_KEY byte XOR 0x5A. If you change the AES key, regenerate OBF. (e.g. 0x31 ^ 0x5A = 0x6B, 0x6B ^ 0x5A = 0x31, …)

- [ ] **Step 2: `DexDecryptor.java`**

```java
package com.apkharden.shell;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;

final class DexDecryptor {
    static byte[] decrypt(byte[] blob) throws Exception {
        byte[] iv = Arrays.copyOfRange(blob, 0, 16);
        byte[] body = Arrays.copyOfRange(blob, 16, blob.length);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(Constants.aesKey(), "AES"),
                new IvParameterSpec(iv));
        return cipher.doFinal(body);
    }

    private DexDecryptor() {}
}
```

- [ ] **Step 3: `AntiDebug.java`**

```java
package com.apkharden.shell;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Debug;
import java.io.BufferedReader;
import java.io.FileReader;

final class AntiDebug {
    /** @return true if a debugger / tracer is detected. */
    static boolean isDetected(Context ctx) {
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return true;
        if ((ctx.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) return true;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("TracerPid:")) {
                    String v = line.substring("TracerPid:".length()).trim();
                    if (!"0".equals(v)) return true;
                    break;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private AntiDebug() {}
}
```

- [ ] **Step 4: `AntiTamper.java`**

```java
package com.apkharden.shell;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import java.security.MessageDigest;

final class AntiTamper {
    /** @return true if the current signing cert matches the expected hash. */
    @SuppressWarnings("deprecation")
    static boolean verify(Context ctx, String expectedHashHex) {
        try {
            PackageManager pm = ctx.getPackageManager();
            String pkg = ctx.getPackageName();
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                SigningInfo si = pi.signingInfo;
                sigs = si.hasMultipleSigners()
                        ? si.getApkContentsSigners()
                        : si.getSigningCertificateHistory();
            } else {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
                sigs = pi.signatures;
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Signature s : sigs) {
                byte[] d = md.digest(s.toByteArray());
                StringBuilder sb = new StringBuilder();
                for (byte b : d) sb.append(String.format("%02x", b));
                if (sb.toString().equalsIgnoreCase(expectedHashHex)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private AntiTamper() {}
}
```

- [ ] **Step 5: `ProxyApplication.java`** (the core reflection)

```java
package com.apkharden.shell;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.dalvik.system.InMemoryDexClassLoader; // see note below
import android.os.Bundle;
import android.os.Process;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class ProxyApplication extends Application {

    private String realAppName = "";

    @Override
    protected void attachBaseContext(Context base) {
        try {
            Bundle md = readMetaData(base);
            String expectedHash = md.getString(Constants.META_SIG_HASH, "");
            realAppName = md.getString(Constants.META_APP_NAME, "");
            int dexCount = md.getInt(Constants.META_DEX_COUNT, 0);

            // 1. Security checks (fail-closed).
            if (AntiDebug.isDetected(base) || !AntiTamper.verify(base, expectedHash)) {
                kill();
                return;
            }

            // 2. Decrypt original dexes into memory.
            ByteBuffer[] buffers = new ByteBuffer[dexCount];
            for (int i = 0; i < dexCount; i++) {
                byte[] enc = readAsset(base, Constants.ENC_DIR + "/" + i);
                buffers[i] = ByteBuffer.wrap(DexDecryptor.decrypt(enc));
            }

            // 3. In-memory classloader; parent = the boot PathClassLoader (holds shell classes).
            ClassLoader parent = base.getClassLoader();
            ClassLoader dexLoader = new dalvik.system.InMemoryDexClassLoader(buffers, parent);

            // 4. Swap LoadedApk.mClassLoader so the framework resolves original classes.
            replaceLoadedApkClassLoader(base, dexLoader);

            super.attachBaseContext(base);
        } catch (Throwable t) {
            // Any failure: do not run a half-initialized process.
            kill();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (realAppName == null || realAppName.isEmpty()) return; // app had no custom Application
        try {
            ClassLoader cl = getClassLoader(); // == dexLoader after swap
            Application realApp = (Application) cl.loadClass(realAppName).newInstance();

            // realApp.attach(baseContext) — hidden Application#attach(Context)
            java.lang.reflect.Method attach =
                    Application.class.getDeclaredMethod("attach", Context.class);
            attach.setAccessible(true);
            attach.invoke(realApp, getBaseContext());

            swapActivityThreadApplication(realApp);

            realApp.onCreate();
        } catch (Throwable t) {
            // If delegation fails the app is unusable; fail loudly in dev, silent in prod.
            throw new RuntimeException("ApkHarden: failed to start real application", t);
        }
    }

    // ---- reflection helpers ----

    private void replaceLoadedApkClassLoader(Context base, ClassLoader cl) throws Exception {
        Object loadedApk = field(base.getClass(), "mPackageInfo").get(base); // ContextImpl.mPackageInfo
        field(loadedApk.getClass(), "mClassLoader").set(loadedApk, cl);
    }

    @SuppressWarnings("unchecked")
    private void swapActivityThreadApplication(Application realApp) throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object current = at.getMethod("currentActivityThread").invoke(null);

        field(at, "mInitialApplication").set(current, realApp);

        Object all = field(at, "mAllApplications").get(current);
        if (all instanceof List) {
            List<Application> list = (List<Application>) all;
            list.remove(this);
            if (!list.contains(realApp)) list.add(realApp);
        }
        Object loadedApk = field(getBaseContext().getClass(), "mPackageInfo").get(getBaseContext());
        field(loadedApk.getClass(), "mApplication").set(loadedApk, realApp);
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        Class<?> k = c;
        while (k != null) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) { k = k.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    private Bundle readMetaData(Context base) throws PackageManager.NameNotFoundException {
        ApplicationInfo ai = base.getPackageManager()
                .getApplicationInfo(base.getPackageName(), PackageManager.GET_META_DATA);
        return ai.metaData != null ? ai.metaData : new Bundle();
    }

    private byte[] readAsset(Context base, String path) throws Exception {
        try (InputStream in = base.getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private void kill() {
        Process.killProcess(Process.myPid());
        System.exit(0);
    }
}
```

> Note: remove the stray `import android.dalvik.system.InMemoryDexClassLoader;` line — the code uses the fully-qualified `dalvik.system.InMemoryDexClassLoader`. (It is listed above only to flag it; do not keep an invalid import.)

- [ ] **Step 6: Create `scripts/build-shell.ps1`**

```powershell
# Compiles the Java shell and produces src/main/resources/shell.dex
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$sdk = $env:ANDROID_HOME; if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { throw "Set ANDROID_HOME to your Android SDK path" }

$platform = Get-ChildItem "$sdk\platforms" -Directory | Sort-Object Name -Descending | Select-Object -First 1
$androidJar = Join-Path $platform.FullName "android.jar"
$buildTools = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1
$d8 = Join-Path $buildTools.FullName "d8.bat"

$out = Join-Path $root "build\shell-classes"
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $out | Out-Null

$srcs = Get-ChildItem "$root\shell" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& javac -source 8 -target 8 -cp $androidJar -d $out $srcs

$dexOut = Join-Path $root "build\shell-dex"
New-Item -ItemType Directory -Force $dexOut | Out-Null
$classes = Get-ChildItem $out -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& $d8 --min-api 26 --output $dexOut $classes --lib $androidJar

Copy-Item (Join-Path $dexOut "classes.dex") (Join-Path $root "src\main\resources\shell.dex") -Force
Write-Host "shell.dex written to src/main/resources/shell.dex"
```

- [ ] **Step 7: Build the shell**

Run: `pwsh scripts/build-shell.ps1`
Expected: `src/main/resources/shell.dex` exists (a few KB). Fix any `javac` errors (the most likely: the stray import noted in Step 5).

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: java shell (proxy app, anti-debug, anti-tamper) + build-shell script + shell.dex"
```

---

## Task 4: ApkReader (TDD)

Reads a target APK as a zip: enumerate entries, isolate `classes*.dex`, expose raw `AndroidManifest.xml` bytes.

**Files:**
- Create: `src/main/kotlin/com/apkharden/packager/core/ApkReader.kt`
- Test: `src/test/kotlin/com/apkharden/packager/core/ApkReaderTest.kt`

- [ ] **Step 1: Write the failing test** (builds a synthetic apk-like zip in a temp dir)

```kotlin
package com.apkharden.packager.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkReaderTest {
    @TempDir lateinit var tmp: File

    private fun fakeApk(): File {
        val f = File(tmp, "in.apk")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(byteArrayOf(3, 0, 8, 0)); z.closeEntry()
            z.putNextEntry(ZipEntry("classes.dex")); z.write("dex0".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("classes2.dex")); z.write("dex1".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("res/layout/a.xml")); z.write("x".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("META-INF/CERT.RSA")); z.write("old".toByteArray()); z.closeEntry()
        }
        return f
    }

    @Test
    fun `finds dex entries in order`() {
        ApkReader(fakeApk()).use { r ->
            assertEquals(listOf("classes.dex", "classes2.dex"), r.dexNames())
            assertArrayEquals("dex0".toByteArray(), r.read("classes.dex"))
        }
    }

    @Test
    fun `reads manifest bytes`() {
        ApkReader(fakeApk()).use { r ->
            assertArrayEquals(byteArrayOf(3, 0, 8, 0), r.manifestBytes())
        }
    }

    @Test
    fun `lists all entry names`() {
        ApkReader(fakeApk()).use { r ->
            assertTrue(r.entryNames().contains("res/layout/a.xml"))
            assertTrue(r.entryNames().contains("META-INF/CERT.RSA"))
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*ApkReaderTest*"`
Expected: FAIL — `ApkReader` unresolved.

- [ ] **Step 3: Implement `ApkReader.kt`**

```kotlin
package com.apkharden.packager.core

import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

class ApkReader(file: File) : Closeable {
    private val zip = ZipFile(file)

    fun entryNames(): List<String> = zip.entries().toList().map { it.name }

    fun dexNames(): List<String> =
        entryNames().filter { it.matches(Regex("classes\\d*\\.dex")) }
            .sortedBy { name -> // classes.dex first, then classes2, classes3...
                val n = name.removePrefix("classes").removeSuffix(".dex")
                if (n.isEmpty()) 1 else n.toInt()
            }

    fun read(name: String): ByteArray {
        val e = zip.getEntry(name) ?: throw IllegalArgumentException("Missing entry: $name")
        return zip.getInputStream(e).use { it.readBytes() }
    }

    fun manifestBytes(): ByteArray = read("AndroidManifest.xml")

    override fun close() = zip.close()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "*ApkReaderTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: apk reader (entries, dex, manifest bytes)"
```

---

## Task 5: ManifestPatcher (TDD)

Reads the original application class and rewrites the binary manifest to use `ProxyApplication` + adds meta-data. Uses ARSCLib. The readback test is the guard against ARSCLib API drift — if a method name differs in 1.3.4, fix the call so the test passes.

**Files:**
- Create: `src/main/kotlin/com/apkharden/packager/core/ManifestPatcher.kt`
- Test: `src/test/kotlin/com/apkharden/packager/core/ManifestPatcherTest.kt`

- [ ] **Step 1: Write the failing test** (builds a real binary manifest with ARSCLib, patches, reads back)

```kotlin
package com.apkharden.packager.core

import com.reandroid.apk.xmlencoder.* // not needed; placeholder removed in impl
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ManifestPatcherTest {

    private fun baseManifest(appName: String?): ByteArray {
        val m = AndroidManifestBlock()
        m.packageName = "com.example.demo"
        if (appName != null) m.applicationClassName = appName
        m.refresh()
        return m.bytes
    }

    @Test
    fun `reads original application class name`() {
        val bytes = baseManifest("com.example.demo.MyApp")
        assertEquals("com.example.demo.MyApp", ManifestPatcher.readApplicationClass(bytes))
    }

    @Test
    fun `null when no custom application`() {
        val bytes = baseManifest(null)
        assertNull(ManifestPatcher.readApplicationClass(bytes))
    }

    @Test
    fun `patch sets proxy and adds meta-data`() {
        val patched = ManifestPatcher.patch(
            manifestBytes = baseManifest("com.example.demo.MyApp"),
            originalAppClass = "com.example.demo.MyApp",
            sigHash = "abc123",
            dexCount = 2,
        )
        assertEquals(Constants.PROXY_APPLICATION, ManifestPatcher.readApplicationClass(patched))
        val md = ManifestPatcher.readMetaData(patched)
        assertEquals("com.example.demo.MyApp", md[Constants.META_APP_NAME])
        assertEquals("abc123", md[Constants.META_SIG_HASH])
        assertEquals("2", md[Constants.META_DEX_COUNT])
    }
}
```

> Remove the unused `xmlencoder` import — it is shown only to indicate the package root. Keep only what compiles.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*ManifestPatcherTest*"`
Expected: FAIL — `ManifestPatcher` unresolved.

- [ ] **Step 3: Implement `ManifestPatcher.kt`**

```kotlin
package com.apkharden.packager.core

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement

object ManifestPatcher {

    private const val ID_name = 0x01010003   // android:name
    private const val ID_value = 0x01010024  // android:value

    fun readApplicationClass(manifestBytes: ByteArray): String? {
        val m = AndroidManifestBlock.load(manifestBytes.inputStream())
        return m.applicationClassName
    }

    fun readMetaData(manifestBytes: ByteArray): Map<String, String> {
        val m = AndroidManifestBlock.load(manifestBytes.inputStream())
        val app = m.applicationElement ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (child in app.listElements("meta-data")) {
            val name = child.searchAttributeByResourceId(ID_name)?.valueAsString ?: continue
            val value = child.searchAttributeByResourceId(ID_value)?.valueAsString ?: ""
            out[name] = value
        }
        return out
    }

    fun patch(
        manifestBytes: ByteArray,
        originalAppClass: String?,
        sigHash: String,
        dexCount: Int,
    ): ByteArray {
        val m = AndroidManifestBlock.load(manifestBytes.inputStream())
        m.applicationClassName = Constants.PROXY_APPLICATION

        val app = m.applicationElement
            ?: throw IllegalStateException("Manifest has no <application> element")

        addMeta(app, Constants.META_APP_NAME, originalAppClass ?: "")
        addMeta(app, Constants.META_SIG_HASH, sigHash)
        addMeta(app, Constants.META_DEX_COUNT, dexCount.toString())

        m.refresh()
        return m.bytes
    }

    private fun addMeta(app: ResXmlElement, name: String, value: String) {
        val meta = app.createChildElement("meta-data")
        meta.getOrCreateAndroidAttribute("name", ID_name).valueAsString = name
        meta.getOrCreateAndroidAttribute("value", ID_value).valueAsString = value
    }
}
```

> ARSCLib API guard: if any of `applicationClassName`, `applicationElement`, `listElements`,
> `createChildElement`, `getOrCreateAndroidAttribute`, `searchAttributeByResourceId`, or
> `m.bytes` differs in the resolved 1.3.4 jar, adjust to the equivalent call until the Step 1
> tests pass. Do not change the test assertions.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "*ManifestPatcherTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: binary manifest patcher (proxy app + meta-data)"
```

---

## Task 6: ApkRepackager (TDD)

Builds the new **unsigned** APK zip: copy every original entry except `classes*.dex`, `AndroidManifest.xml`, and `META-INF/*` signature files; write the patched manifest; write `shell.dex` as `classes.dex`; write encrypted dex assets.

**Files:**
- Create: `src/main/kotlin/com/apkharden/packager/core/ApkRepackager.kt`
- Test: `src/test/kotlin/com/apkharden/packager/core/ApkRepackagerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.apkharden.packager.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ApkRepackagerTest {
    @TempDir lateinit var tmp: File

    private fun fakeApk(): File {
        val f = File(tmp, "in.apk")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write("OLDMANIFEST".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("classes.dex")); z.write("dex0".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("res/a")); z.write("RES".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("META-INF/CERT.RSA")); z.write("OLDSIG".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("META-INF/MANIFEST.MF")); z.write("MF".toByteArray()); z.closeEntry()
        }
        return f
    }

    private fun names(f: File) = ZipFile(f).use { it.entries().toList().map { e -> e.name } }
    private fun read(f: File, n: String) = ZipFile(f).use { z -> z.getInputStream(z.getEntry(n)).readBytes() }

    @Test
    fun `repackages with shell dex, patched manifest, encrypted assets, no old sig`() {
        val out = File(tmp, "out.apk")
        ApkRepackager.repackage(
            input = fakeApk(),
            output = out,
            patchedManifest = "NEWMANIFEST".toByteArray(),
            shellDex = "SHELL".toByteArray(),
            encryptedDexes = listOf("ENC0".toByteArray(), "ENC1".toByteArray()),
        )
        val n = names(out)
        assertTrue(n.contains("res/a"))
        assertEquals("RES", String(read(out, "res/a")))
        assertEquals("NEWMANIFEST", String(read(out, "AndroidManifest.xml")))
        assertEquals("SHELL", String(read(out, "classes.dex")))
        assertEquals("ENC0", String(read(out, Constants.encryptedDexEntry(0))))
        assertEquals("ENC1", String(read(out, Constants.encryptedDexEntry(1))))
        assertFalse(n.any { it.startsWith("META-INF/") }) // old signatures dropped
        assertFalse(n.contains("classes2.dex"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*ApkRepackagerTest*"`
Expected: FAIL — `ApkRepackager` unresolved.

- [ ] **Step 3: Implement `ApkRepackager.kt`**

```kotlin
package com.apkharden.packager.core

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ApkRepackager {

    private val dexRegex = Regex("classes\\d*\\.dex")

    fun repackage(
        input: File,
        output: File,
        patchedManifest: ByteArray,
        shellDex: ByteArray,
        encryptedDexes: List<ByteArray>,
    ) {
        ZipFile(input).use { zin ->
            ZipOutputStream(output.outputStream()).use { zout ->
                // 1. Copy originals except dex, manifest, and old signatures.
                for (e in zin.entries()) {
                    val name = e.name
                    if (name.matches(dexRegex)) continue
                    if (name == "AndroidManifest.xml") continue
                    if (name.startsWith("META-INF/") &&
                        (name.endsWith(".RSA") || name.endsWith(".DSA") ||
                         name.endsWith(".EC") || name.endsWith(".SF") ||
                         name == "META-INF/MANIFEST.MF")
                    ) continue

                    val copy = ZipEntry(name)
                    zout.putNextEntry(copy)
                    zin.getInputStream(e).use { it.copyTo(zout) }
                    zout.closeEntry()
                }
                // 2. Patched manifest.
                write(zout, "AndroidManifest.xml", patchedManifest)
                // 3. Shell becomes classes.dex.
                write(zout, "classes.dex", shellDex)
                // 4. Encrypted original dexes as assets.
                encryptedDexes.forEachIndexed { i, bytes ->
                    write(zout, Constants.encryptedDexEntry(i), bytes)
                }
            }
        }
    }

    private fun write(zout: ZipOutputStream, name: String, bytes: ByteArray) {
        zout.putNextEntry(ZipEntry(name))
        zout.write(bytes)
        zout.closeEntry()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "*ApkRepackagerTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: apk repackager (swap dex, manifest, encrypted assets, strip old sig)"
```

---

## Task 7: ApkSignerWrapper (TDD)

Signs the unsigned APK with apksig (V1+V2+V3) and exposes a verify helper.

**Files:**
- Create: `src/main/kotlin/com/apkharden/packager/core/ApkSignerWrapper.kt`
- Test: `src/test/kotlin/com/apkharden/packager/core/ApkSignerWrapperTest.kt`

- [ ] **Step 1: Write the failing test** (signs a minimal valid apk zip — needs a real binary manifest)

```kotlin
package com.apkharden.packager.core

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkSignerWrapperTest {
    @TempDir lateinit var tmp: File
    private val ks = File("src/test/resources/test.jks")

    private fun minimalApk(): File {
        val manifest = AndroidManifestBlock().apply {
            packageName = "com.example.demo"; refresh()
        }.bytes
        val f = File(tmp, "unsigned.apk")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(manifest); z.closeEntry()
            z.putNextEntry(ZipEntry("classes.dex")); z.write(ByteArray(64)); z.closeEntry()
        }
        return f
    }

    @Test
    fun `signs and verifies`() {
        val out = File(tmp, "signed.apk")
        val creds = KeystoreUtil.load(ks, "123456", "test", "123456")
        ApkSignerWrapper.sign(minimalApk(), out, creds)
        assertTrue(out.exists() && out.length() > 0)
        assertTrue(ApkSignerWrapper.verify(out))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*ApkSignerWrapperTest*"`
Expected: FAIL — `ApkSignerWrapper` unresolved.

- [ ] **Step 3: Implement `ApkSignerWrapper.kt`**

```kotlin
package com.apkharden.packager.core

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File

object ApkSignerWrapper {

    fun sign(input: File, output: File, creds: KeystoreCredentials) {
        val signerConfig = ApkSigner.SignerConfig.Builder(
            "CERT",
            creds.privateKey,
            creds.certificates,
        ).build()

        val signer = ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
        signer.sign() // apksig also 4-byte aligns uncompressed entries
    }

    fun verify(apk: File): Boolean {
        val result = ApkVerifier.Builder(apk).build().verify()
        return result.isVerified
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "*ApkSignerWrapperTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: apksig signing + verification wrapper"
```

---

## Task 8: HardenPipeline (integration TDD)

Orchestrates the full flow and is the public entry the GUI calls.

**Files:**
- Create: `src/main/kotlin/com/apkharden/packager/core/HardenPipeline.kt`
- Test: `src/test/kotlin/com/apkharden/packager/core/HardenPipelineTest.kt`

- [ ] **Step 1: Write the failing test** (synthetic but real-structured apk; asserts end-state)

```kotlin
package com.apkharden.packager.core

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class HardenPipelineTest {
    @TempDir lateinit var tmp: File
    private val ks = File("src/test/resources/test.jks")

    private fun appApk(appClass: String?): File {
        val manifest = AndroidManifestBlock().apply {
            packageName = "com.example.demo"
            if (appClass != null) applicationClassName = appClass
            refresh()
        }.bytes
        val f = File(tmp, "app.apk")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(manifest); z.closeEntry()
            z.putNextEntry(ZipEntry("classes.dex")); z.write("REALDEX".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("resources.arsc")); z.write(ByteArray(8)); z.closeEntry()
        }
        return f
    }

    @Test
    fun `hardens app end to end`() {
        val out = File(tmp, "hardened.apk")
        val logs = mutableListOf<String>()
        HardenPipeline.harden(
            input = appApk("com.example.demo.MyApp"),
            output = out,
            keystore = ks, storePass = "123456", alias = "test", keyPass = "123456",
            log = { logs.add(it) },
        )

        assertTrue(ApkSignerWrapper.verify(out))
        ZipFile(out).use { z ->
            // shell is now classes.dex; original real dex is encrypted in assets/d/0
            assertEquals("SHELL_OR_REAL_DEX_PRESENT",
                if (z.getEntry("classes.dex") != null) "SHELL_OR_REAL_DEX_PRESENT" else "MISSING")
            assertNotNull(z.getEntry(Constants.encryptedDexEntry(0)))
            val enc = z.getInputStream(z.getEntry(Constants.encryptedDexEntry(0))).readBytes()
            assertFalse(enc.contentEquals("REALDEX".toByteArray())) // encrypted, not plaintext
            // manifest now points at proxy
            val mBytes = z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes()
            assertEquals(Constants.PROXY_APPLICATION, ManifestPatcher.readApplicationClass(mBytes))
            val md = ManifestPatcher.readMetaData(mBytes)
            assertEquals("com.example.demo.MyApp", md[Constants.META_APP_NAME])
            assertEquals("1", md[Constants.META_DEX_COUNT])
            assertEquals(64, md[Constants.META_SIG_HASH]!!.length)
        }
        assertTrue(logs.isNotEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*HardenPipelineTest*"`
Expected: FAIL — `HardenPipeline` unresolved.

- [ ] **Step 3: Implement `HardenPipeline.kt`**

```kotlin
package com.apkharden.packager.core

import java.io.File

object HardenPipeline {

    /** Loads the embedded prebuilt shell.dex from resources. */
    private fun loadShellDex(): ByteArray =
        (javaClass.getResourceAsStream("/shell.dex")
            ?: error("shell.dex missing from resources — run scripts/build-shell.ps1"))
            .use { it.readBytes() }

    fun harden(
        input: File,
        output: File,
        keystore: File,
        storePass: String,
        alias: String,
        keyPass: String,
        log: (String) -> Unit = {},
    ) {
        require(input.exists()) { "Input APK not found: $input" }
        require(keystore.exists()) { "Keystore not found: $keystore" }

        log("Loading keystore…")
        val creds = KeystoreUtil.load(keystore, storePass, alias, keyPass)
        val sigHash = KeystoreUtil.expectedSigHash(creds)

        log("Reading APK…")
        val (manifestBytes, originalApp, encryptedDexes) = ApkReader(input).use { r ->
            val dexes = r.dexNames()
            require(dexes.isNotEmpty()) { "APK contains no classes.dex" }
            log("Encrypting ${dexes.size} dex file(s)…")
            val enc = dexes.map { DexEncryptor.encrypt(r.read(it)) }
            val manifest = r.manifestBytes()
            Triple(manifest, ManifestPatcher.readApplicationClass(manifest), enc)
        }

        log("Patching manifest (entry → ProxyApplication)…")
        val patchedManifest = ManifestPatcher.patch(
            manifestBytes = manifestBytes,
            originalAppClass = originalApp,
            sigHash = sigHash,
            dexCount = encryptedDexes.size,
        )

        log("Repackaging…")
        val unsigned = File.createTempFile("apkharden-unsigned", ".apk")
        try {
            ApkRepackager.repackage(
                input = input,
                output = unsigned,
                patchedManifest = patchedManifest,
                shellDex = loadShellDex(),
                encryptedDexes = encryptedDexes,
            )
            log("Signing (V1+V2+V3)…")
            ApkSignerWrapper.sign(unsigned, output, creds)
        } finally {
            unsigned.delete()
        }

        check(ApkSignerWrapper.verify(output)) { "Output APK failed signature verification" }
        log("Done → ${output.absolutePath}")
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "*HardenPipelineTest*"`
Expected: PASS. (Requires `shell.dex` from Task 3 present in resources.)

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`
Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: harden pipeline orchestration + integration test"
```

---

## Task 9: Compose GUI

Wires the pipeline to a simple UI: pick input APK, output path, keystore + alias/passwords, harden button, log panel.

**Files:**
- Create: `src/main/kotlin/com/apkharden/packager/ui/HardenScreen.kt`
- Modify: `src/main/kotlin/com/apkharden/packager/Main.kt`

- [ ] **Step 1: Implement `HardenScreen.kt`**

```kotlin
package com.apkharden.packager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.apkharden.packager.core.HardenPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
fun HardenScreen() {
    var inputApk by remember { mutableStateOf("") }
    var outputApk by remember { mutableStateOf("") }
    var keystore by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }
    var storePass by remember { mutableStateOf("") }
    var keyPass by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val logs = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()

    fun pick(title: String, save: Boolean = false): String? {
        val d = FileDialog(null as Frame?, title, if (save) FileDialog.SAVE else FileDialog.LOAD)
        d.isVisible = true
        return d.file?.let { File(d.directory, it).absolutePath }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ApkHarden — 基础加固", style = MaterialTheme.typography.h6)

        fileRow("输入 APK", inputApk, { inputApk = it }) { pick("选择 APK")?.let { p ->
            inputApk = p
            if (outputApk.isBlank()) outputApk = p.removeSuffix(".apk") + "-hardened.apk"
        } }
        fileRow("输出 APK", outputApk, { outputApk = it }) { pick("输出 APK", save = true)?.let { outputApk = it } }
        fileRow("Keystore", keystore, { keystore = it }) { pick("选择 keystore")?.let { keystore = it } }

        OutlinedTextField(alias, { alias = it }, label = { Text("别名 alias") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(storePass, { storePass = it }, label = { Text("keystore 密码") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(keyPass, { keyPass = it }, label = { Text("key 密码") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Button(
            enabled = !running && inputApk.isNotBlank() && outputApk.isNotBlank() && keystore.isNotBlank() && alias.isNotBlank(),
            onClick = {
                logs.clear(); running = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            HardenPipeline.harden(
                                input = File(inputApk), output = File(outputApk),
                                keystore = File(keystore), storePass = storePass, alias = alias, keyPass = keyPass,
                                log = { line -> scope.launch { logs.add(line) } },
                            )
                        }
                        logs.add("✅ 加固成功")
                    } catch (e: Throwable) {
                        logs.add("❌ 失败: ${e.message}")
                    } finally { running = false }
                }
            },
        ) { Text(if (running) "加固中…" else "开始加固") }

        Divider()
        Text("日志", style = MaterialTheme.typography.subtitle2)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            logs.forEach { Text(it, style = MaterialTheme.typography.body2) }
        }
    }
}

@Composable
private fun fileRow(label: String, value: String, onChange: (String) -> Unit, onPick: () -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f))
        Button(onClick = onPick) { Text("浏览") }
    }
}
```

- [ ] **Step 2: Update `Main.kt`**

```kotlin
package com.apkharden.packager

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.apkharden.packager.ui.HardenScreen

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "ApkHarden",
        state = rememberWindowState(width = 720.dp, height = 640.dp),
    ) {
        MaterialTheme { HardenScreen() }
    }
}
```

- [ ] **Step 3: Run the app**

Run: `./gradlew run`
Expected: window opens; pick a real signed APK + the test keystore, harden, watch the log reach "✅ 加固成功", and confirm the output file exists.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: compose desktop GUI for hardening"
```

---

## Task 10: Sample apps + on-device e2e verification

The reflection in `ProxyApplication` can only be validated on a device/emulator. Build two tiny sample apps and verify behavior on API 26, 30, and 34.

**Files:**
- Create: `samples/README.md` (instructions + checklist)

- [ ] **Step 1: Prepare two sample debug APKs**

Build (in Android Studio or via an existing project) two minimal apps, each signed with the **test.jks** key (so the expected-hash matches):
1. `sample-plain` — default `Application`, one Activity showing "Hello".
2. `sample-customapp` — a custom `class MyApp : Application()` that logs in `onCreate`, one Activity.

Copy both APKs into `samples/`.

- [ ] **Step 2: Harden each sample**

Run the GUI (`./gradlew run`), harden each sample APK with `samples/test.jks` (copy from `src/test/resources/test.jks`), alias `test`, both passwords `123456`.

- [ ] **Step 3: Install + launch (per API level 26 / 30 / 34)**

Run:
```
adb install -r samples/sample-plain-hardened.apk
adb shell am start -n <package>/.MainActivity
adb logcat -s ApkHarden AndroidRuntime
```
Expected: app launches and shows its UI; no `ClassNotFoundException`; for `sample-customapp`, its `onCreate` log appears (delegation works).

- [ ] **Step 4: Verify anti-repackaging**

Re-sign a hardened APK with a *different* key and install:
```
apksigner sign --ks <other.jks> --out tampered.apk samples/sample-plain-hardened.apk
adb install -r tampered.apk
adb shell am start -n <package>/.MainActivity
```
Expected: process exits immediately (signature hash mismatch) — Activity does not appear.

- [ ] **Step 5: Verify anti-debug**

Run:
```
adb shell am start -D -n <package>/.MainActivity
```
Expected: app detects the waiting-for-debugger / tracer state and exits.

- [ ] **Step 6: Write `samples/README.md`** capturing the above checklist and the per-API results table.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "test: sample apps + on-device e2e verification checklist"
```

---

## Self-Review (completed by author)

- **Spec coverage:** DEX shell (Tasks 1,3,4,6,8) ✓ · anti-repackaging (Task 3 AntiTamper + Task 2 hash + Task 5 meta-data) ✓ · anti-debug (Task 3 AntiDebug) ✓ · self-contained packaging via ARSCLib+apksig (Tasks 5,7) ✓ · Compose GUI (Task 9) ✓ · minSdk 26 InMemoryDexClassLoader (Task 3) ✓ · multidex (ApkReader.dexNames + pipeline loop) ✓ · no-custom-Application path (ProxyApplication.onCreate guard + pipeline empty APP_NAME) ✓ · testing (unit Tasks 1–8 + e2e Task 10) ✓.
- **Deviation from spec:** shell implemented in **Java** instead of Kotlin (minimal dex, avoids kotlin-stdlib clash). Documented at plan top.
- **Type consistency:** `KeystoreCredentials`, `HardenPipeline.harden(...)`, `ManifestPatcher.patch/readApplicationClass/readMetaData`, `ApkRepackager.repackage(...)`, `Constants.encryptedDexEntry(i)` referenced consistently across tasks.
- **Known external-API risk:** ARSCLib 1.3.4 method names (Task 5) — guarded by readback test; adjust calls (not assertions) if names differ.
- **Placeholder scan:** test files contain two intentionally-noted unused imports (Tasks 5 Step 1, marked for removal); no TODO/TBD remain.
