# File-based DexClassLoader Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the shell's in-memory dex loading with decrypt-to-disk + `DexClassLoader` (persistent, version-keyed cache) to recover near-original runtime performance and lower the compatibility floor to Android 6.0 (API 23).

**Architecture:** The packager side is unchanged — dexes are still deflate+AES'd into `assets/d/*`. Only the runtime shell changes: `ProxyApplication.attachBaseContext` now decrypts each dex to an app-private, `versionCode`-keyed directory (written atomically, reused across launches so ART can build and reuse an oat), then loads them with `DexClassLoader(paths, oatDir, nativeLibDir, parent)` and swaps `LoadedApk.mClassLoader` as before. `shell.dex` is recompiled at `--min-api 23`.

**Tech Stack:** Java (Android shell, compiled to `shell.dex` via `scripts/build-shell.ps1` using `d8` from `C:\AndroidSdk`), Kotlin/Gradle (packager), JUnit (packager tests), adb + Android emulator for on-device verification.

**Note on testing:** The shell (`ProxyApplication`) requires the Android runtime and cannot be JVM-unit-tested in this project. For shell tasks the "test" is the end-to-end **install → launch → logcat → screenshot** check on a real device/emulator. The packager side keeps its existing JUnit tests (they must stay green since packaging is unchanged).

**Environment constants (this machine):**
- Android SDK: `C:\AndroidSdk` (set `$env:ANDROID_HOME="C:\AndroidSdk"` before `build-shell.ps1`).
- Real device: Huawei P30 `ELE-L29`, Android 10 / API 29, arm64-v8a (`adb` serial visible via `adb devices`).
- Test keystore: `src/test/resources/test.jks` (storePass `123456`, alias `test`, keyPass `123456`).
- Real input APK: `C:/Users/huqiang/StudioProjects/merchant-android/app/build/outputs/apk/product_64/merchant_20260615_3.7.15_product_64.apk` (arm64-v8a only).
- Hardened app package: `com.qekj.merchant`, launcher `com.qekj.merchant.ui.activity.SplashActivity`.

---

## Task 1: Lower shell compile floor to API 23

**Files:**
- Modify: `scripts/build-shell.ps1:37`

Audit result (already done while writing this plan — no code change needed):
- `AntiTamper.java` already branches on `Build.VERSION.SDK_INT >= P` and falls back to `GET_SIGNATURES` for older APIs → API 23 OK.
- `AntiDebug.java` uses only API-1 calls (`Debug.isDebuggerConnected`, `FLAG_DEBUGGABLE`, `/proc/self/status`) → API 23 OK.

- [ ] **Step 1: Change the d8 min-api flag**

In `scripts/build-shell.ps1`, the `d8` invocation (line ~37):

```powershell
& $d8 --min-api 26 --output $dexOut $classes --lib $androidJar
```

becomes:

```powershell
& $d8 --min-api 23 --output $dexOut $classes --lib $androidJar
```

- [ ] **Step 2: Commit**

```bash
git add scripts/build-shell.ps1
git commit -m "build: compile shell.dex at min-api 23 for Android 6.0 support"
```

---

## Task 2: Rewrite ProxyApplication to file-based DexClassLoader

**Files:**
- Modify: `shell/com/apkharden/shell/ProxyApplication.java`

This task only edits Java source. `shell.dex` is rebuilt in Task 3 and verified in Tasks 5–6.

- [ ] **Step 1: Update imports**

In `ProxyApplication.java`, the import block currently includes `import java.nio.ByteBuffer;` (used only by `InMemoryDexClassLoader`, which we remove) and lacks `java.io.File` / `PackageInfo`. Replace the import section so it reads:

```java
import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Process;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
```

(Removed: `java.nio.ByteBuffer`. Added: `PackageInfo`, `java.io.File`, `FileInputStream`, `FileOutputStream`, `IOException`.)

- [ ] **Step 2: Replace the decrypt + classloader block in `attachBaseContext`**

Find this block (steps 2–4 of the current method, after the security check):

```java
            // 2. Decrypt original dexes into memory.
            ByteBuffer[] buffers = new ByteBuffer[dexCount];
            for (int i = 0; i < dexCount; i++) {
                byte[] enc = readAsset(base, Constants.ENC_DIR + "/" + i);
                buffers[i] = ByteBuffer.wrap(DexDecryptor.decrypt(enc));
            }

            // 3. In-memory classloader; parent = the boot PathClassLoader (holds shell classes).
            ClassLoader parent = base.getClassLoader();
            ClassLoader dexLoader = new dalvik.system.InMemoryDexClassLoader(buffers, parent);

            // InMemoryDexClassLoader has no native library search path, so classes loaded by it
            // would fail System.loadLibrary (e.g. libmmkv.so lives in the APK's lib/<abi>/).
            // Copy the original PathClassLoader's native library paths onto our loader.
            copyNativeLibraryPaths(parent, dexLoader);

            // 4. Swap LoadedApk.mClassLoader so the framework resolves original classes.
            replaceLoadedApkClassLoader(base, dexLoader);
```

Replace it with:

```java
            // 2. Decrypt original dexes to an app-private, version-keyed cache. Persisting them
            //    (instead of holding them in memory) lets ART build and reuse an oat file, so the
            //    real code runs AOT-compiled — near-original performance. Re-decrypt only when a
            //    cache file is missing or invalid (e.g. first launch, or after an app update where
            //    the versionCode — and thus the cache dir — changes).
            File cacheDir = base.getDir("apkharden_" + versionCode(base), Context.MODE_PRIVATE);
            StringBuilder dexPath = new StringBuilder();
            for (int i = 0; i < dexCount; i++) {
                File out = new File(cacheDir, "c" + i + ".dex");
                if (!isValidDex(out)) {
                    byte[] plain = DexDecryptor.decrypt(readAsset(base, Constants.ENC_DIR + "/" + i));
                    atomicWrite(out, plain);
                }
                if (dexPath.length() > 0) dexPath.append(File.pathSeparatorChar);
                dexPath.append(out.getAbsolutePath());
            }

            // 3. File-backed classloader. optimizedDirectory is honoured pre-API-26 and ignored
            //    after (ART manages the oat next to the dex either way); the native lib dir lets
            //    System.loadLibrary find libraries bundled in the APK.
            File oatDir = new File(cacheDir, "oat");
            oatDir.mkdirs();
            String nativeLibDir = base.getApplicationInfo().nativeLibraryDir;
            ClassLoader parent = base.getClassLoader();
            ClassLoader dexLoader = new dalvik.system.DexClassLoader(
                    dexPath.toString(), oatDir.getAbsolutePath(), nativeLibDir, parent);

            // 4. Swap LoadedApk.mClassLoader so the framework resolves original classes.
            replaceLoadedApkClassLoader(base, dexLoader);
```

- [ ] **Step 3: Remove `copyNativeLibraryPaths` (no longer needed)**

Delete the entire `copyNativeLibraryPaths` method (the DexClassLoader gets the native lib dir via its constructor now):

```java
    // Copies the native library search path (DexPathList internals) from one classloader to another,
    // so libraries bundled in the APK remain loadable from classes resolved by the in-memory loader.
    private void copyNativeLibraryPaths(ClassLoader from, ClassLoader to) throws Exception {
        Object fromList = field(from.getClass(), "pathList").get(from); // BaseDexClassLoader.pathList
        Object toList = field(to.getClass(), "pathList").get(to);
        String[] fields = {
            "nativeLibraryDirectories",
            "systemNativeLibraryDirectories",
            "nativeLibraryPathElements", // the actual search array used by findLibrary()
        };
        for (String name : fields) {
            try {
                Field f = field(toList.getClass(), name);
                f.set(toList, field(fromList.getClass(), name).get(fromList));
            } catch (NoSuchFieldException ignored) {
                // field set varies across Android versions; copy whatever exists
            }
        }
    }
```

- [ ] **Step 4: Add the three new helper methods**

Add these methods to the class (e.g. just above `private Bundle readMetaData(...)`):

```java
    private int versionCode(Context base) {
        try {
            PackageInfo pi = base.getPackageManager().getPackageInfo(base.getPackageName(), 0);
            return pi.versionCode; // deprecated on API 28+ but still correct; fine at min-api 23
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    // A cached dex is usable only if it exists and starts with the dex magic ("dex\n"). Guards
    // against a half-written file from a process killed mid-write.
    private static boolean isValidDex(File f) {
        if (!f.exists() || f.length() < 40) return false;
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] magic = new byte[4];
            if (in.read(magic) != 4) return false;
            return magic[0] == 'd' && magic[1] == 'e' && magic[2] == 'x' && magic[3] == '\n';
        } catch (Exception e) {
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
    }

    // Write to a temp file + fsync + rename, so a reader never sees a partially-written dex.
    private static void atomicWrite(File out, byte[] data) throws IOException {
        File tmp = new File(out.getAbsolutePath() + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            fos.write(data);
            fos.getFD().sync();
        } finally {
            fos.close();
        }
        if (!tmp.renameTo(out)) {
            out.delete();
            if (!tmp.renameTo(out)) throw new IOException("cache rename failed: " + out);
        }
    }
```

- [ ] **Step 5: Sanity-check the source compiles (javac, no Android runtime needed for this check)**

The full compile happens in Task 3 via `build-shell.ps1`. No separate command here — proceed to Task 3, which fails loudly if the Java is malformed.

- [ ] **Step 6: Commit**

```bash
git add shell/com/apkharden/shell/ProxyApplication.java
git commit -m "feat(shell): load dex from a persistent disk cache via DexClassLoader

Decrypt original dexes to an app-private, versionCode-keyed dir (atomic
write, reused across launches) and load with DexClassLoader so ART can
build/reuse an oat -> near-original runtime perf. Drops InMemoryDexClassLoader
(API 26+) for DexClassLoader (API 1+), lowering the floor to Android 6.0.
Native lib dir is passed to the loader, replacing copyNativeLibraryPaths."
```

---

## Task 3: Rebuild shell.dex

**Files:**
- Modify (binary output): `src/main/resources/shell.dex`

- [ ] **Step 1: Rebuild**

Run (PowerShell):

```powershell
$env:ANDROID_HOME="C:\AndroidSdk"; & "C:\Users\huqiang\StudioProjects\ApkHarden\scripts\build-shell.ps1"
```

Expected: ends with `shell.dex written to src/main/resources/shell.dex`. (Chinese-locale `javac` deprecation warnings about `versionCode`/`GET_SIGNATURES` are expected and harmless.)

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/shell.dex
git commit -m "build: rebuild shell.dex (file-based DexClassLoader, min-api 23)"
```

---

## Task 4: Confirm packager unit tests stay green

**Files:** none (packaging code unchanged; this is a regression gate).

- [ ] **Step 1: Run the full suite**

```bash
cd "C:/Users/huqiang/StudioProjects/ApkHarden" && ./gradlew.bat test --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`, no `FAILED`. (Existing tests: compression round-trip, 4096 `.so` alignment, encrypted-dex size guard, repackager, signer, manifest, keystore.)

---

## Task 5: End-to-end verification on the API 29 device (authoritative)

This is the real test for the shell change. Uses a throwaway harness to harden the real APK with the test key.

**Files:**
- Create (temporary, deleted in Step 6): `src/test/kotlin/com/apkharden/packager/core/_ManualHarden.kt`

- [ ] **Step 1: Create the harden harness**

```kotlin
package com.apkharden.packager.core

import org.junit.jupiter.api.Test
import java.io.File

class _ManualHarden {
    @Test
    fun harden() {
        val input = File("C:/Users/huqiang/StudioProjects/merchant-android/app/build/outputs/apk/product_64/merchant_20260615_3.7.15_product_64.apk")
        if (!input.exists()) { println("INPUT MISSING"); return }
        val out = File("C:/Users/huqiang/StudioProjects/ApkHarden/build/demo-hardened.apk")
        out.parentFile.mkdirs()
        HardenPipeline.harden(
            input = input, output = out,
            keystore = File("src/test/resources/test.jks"),
            storePass = "123456", alias = "test", keyPass = "123456",
        )
        println("OK ${out.length()/1024/1024}MB signed=${ApkSignerWrapper.verify(out)}")
    }
}
```

- [ ] **Step 2: Produce the hardened APK**

```bash
cd "C:/Users/huqiang/StudioProjects/ApkHarden" && ./gradlew.bat test --tests "*_ManualHarden*" --rerun-tasks 2>&1 | grep -E "BUILD|FAILED" | tail -1
ls -la build/demo-hardened.apk
```

Expected: `BUILD SUCCESSFUL`, file ~81MB present.

- [ ] **Step 3: Reinstall on the device**

```bash
adb uninstall com.qekj.merchant
adb install "C:/Users/huqiang/StudioProjects/ApkHarden/build/demo-hardened.apk"
```

Expected: both `Success`. (Uninstall first because the test-key signature differs from any prior install.)

- [ ] **Step 4: Launch (first, cold) and confirm no crash**

```bash
adb logcat -c
adb shell am start -W -n com.qekj.merchant/com.qekj.merchant.ui.activity.SplashActivity
```

Expected from `am start -W`: a `TotalTime: <ms>` line. Record it as **first-launch (cache cold: decrypt + dex2oat)**.

```bash
sleep 6
adb shell pidof com.qekj.merchant
adb logcat -d -v brief | grep -iE "FATAL|AndroidRuntime|UnsatisfiedLink|ClassNotFound.*(qekj|mmkv)|failed to start real" | head -20
```

Expected: `pidof` prints a PID (process alive); the grep prints **no** FATAL/UnsatisfiedLink/failed-to-start lines.

- [ ] **Step 5: Measure warm-cache launch (proves oat reuse / perf recovery)**

```bash
adb shell am force-stop com.qekj.merchant
adb shell am start -W -n com.qekj.merchant/com.qekj.merchant.ui.activity.SplashActivity
```

Expected: a second `TotalTime: <ms>` — should be **meaningfully lower** than the first launch (cache + oat already built). Record both numbers. Capture a screenshot to confirm the UI:

```bash
adb exec-out screencap -p > "C:/Users/huqiang/StudioProjects/ApkHarden/build/verify_screen.png"
```

Read `build/verify_screen.png` and confirm a real app screen renders (splash/login), not a blank frame.

- [ ] **Step 6: Remove the harness + artifacts**

```bash
cd "C:/Users/huqiang/StudioProjects/ApkHarden" && rm -f src/test/kotlin/com/apkharden/packager/core/_ManualHarden.kt build/demo-hardened.apk build/verify_screen.png
```

No commit (only deletions of untracked files).

---

## Task 6: Cross-version check on available emulators (best-effort)

The merchant APK ships **arm64-v8a only**. An x86/x86_64 emulator image without ARM translation cannot load its native libs, so this task first discovers each image's ABI and only runs the full check where the ABI is compatible; otherwise it records the limitation. Installed images: `android-26`, `android-28` (plus 35, 36).

**Files:** none (verification only).

- [ ] **Step 1: Discover ABIs of the API 26 and 28 images**

```bash
ls "C:/AndroidSdk/system-images/android-26"/*/* -d 2>/dev/null
ls "C:/AndroidSdk/system-images/android-28"/*/* -d 2>/dev/null
```

This prints variant/ABI dirs (e.g. `google_apis/x86_64` or `default/arm64-v8a`). Note the ABI for each.

- [ ] **Step 2: Decide per image**

- If an **arm64-v8a** image exists for an API level → it can run the real hardened APK; proceed to Step 3 for it.
- If only **x86_64** (no ARM translation) → the arm64-only APK's `System.loadLibrary` will fail by design (not a shell bug). Record: "API <n>: shell dex-loading not separately verifiable on x86_64 emulator (APK is arm64-only); covered by the API 29 arm64 device test." Skip Steps 3–5 for that image.

- [ ] **Step 3: Create + boot an AVD (for each compatible image)**

Replace `<IMAGE>` with the exact package from Step 1, e.g. `system-images;android-28;default;arm64-v8a`, and `<API>` with 26/28:

```powershell
& "C:\AndroidSdk\cmdline-tools\latest\bin\avdmanager.bat" create avd -n "verify_api<API>" -k "<IMAGE>" --force
Start-Process "C:\AndroidSdk\emulator\emulator.exe" -ArgumentList "-avd","verify_api<API>","-no-snapshot","-no-audio","-no-boot-anim"
```

Wait for boot:

```bash
adb -s emulator-5554 wait-for-device
until [ "$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
echo "booted"
```

- [ ] **Step 4: Re-harden + install + launch on the emulator**

Reuse the harness from Task 5 (recreate `_ManualHarden.kt`, run the gradle test to produce `build/demo-hardened.apk`, delete harness after), then:

```bash
adb -s emulator-5554 install "C:/Users/huqiang/StudioProjects/ApkHarden/build/demo-hardened.apk"
adb -s emulator-5554 logcat -c
adb -s emulator-5554 shell am start -W -n com.qekj.merchant/com.qekj.merchant.ui.activity.SplashActivity
sleep 8
adb -s emulator-5554 shell pidof com.qekj.merchant
adb -s emulator-5554 logcat -d -v brief | grep -iE "FATAL|AndroidRuntime|InMemoryDex|Check failed|failed to start real" | head -20
```

Expected on a compatible ABI: `pidof` returns a PID, no FATAL lines. (`InMemoryDex`/`Check failed` must NOT appear — confirms the old failure mode is gone.)

- [ ] **Step 5: Shut the emulator down**

```bash
adb -s emulator-5554 emu kill
```

- [ ] **Step 6: Record results**

Note per API level: `pass` / `pass (arm64)` / `not separately verifiable (x86_64, arm64-only APK)`. No commit.

---

## Task 7: Documentation, memory, and wrap-up

**Files:**
- Modify: `README.md` (if it documents the loading mechanism / min SDK)
- Modify: `C:\Users\huqiang\.claude\projects\C--Users-huqiang-StudioProjects-ApkHarden\memory\hardening-runtime-gotchas.md`

- [ ] **Step 1: Update README (if applicable)**

If `README.md` mentions in-memory loading, API 26/8.0 floor, or performance, update those sections to state: dex is decrypted to an app-private versionCode-keyed cache and loaded via `DexClassLoader` (AOT-capable); min supported Android is 6.0 (API 23); first launch after install/update does a one-time decrypt + dex2oat (slower), subsequent cold launches reuse the oat. If README has no such section, skip.

- [ ] **Step 2: Update the memory note**

In `hardening-runtime-gotchas.md`, update the cross-version paragraph: the shell now floors at **API 23** (was 26) via file-based `DexClassLoader`; the InMemoryDexClassLoader gotcha is historical. Record verified envs (API 29 device + whichever emulators passed) and the persistent-cache + first-launch-cost behavior.

- [ ] **Step 3: Commit docs**

```bash
git add README.md
git commit -m "docs: file-based DexClassLoader loading + Android 6.0 floor"
```

(Memory files live outside the repo — no git add needed for them.)

- [ ] **Step 4: Rebuild the desktop app so the user's shortcut ships the fix**

```bash
cd "C:/Users/huqiang/StudioProjects/ApkHarden" && ./gradlew.bat createDistributable
```

Then redeploy to the stable install dir (PowerShell):

```powershell
$src="C:\Users\huqiang\StudioProjects\ApkHarden\build\compose\binaries\main\app\ApkHarden"; $dest="C:\Users\huqiang\ApkHarden"
Get-Process ApkHarden -ErrorAction SilentlyContinue | Stop-Process -Force
Remove-Item $dest -Recurse -Force -ErrorAction SilentlyContinue
Copy-Item $src $dest -Recurse -Force
```

Expected: `BUILD SUCCESSFUL`; `C:\Users\huqiang\ApkHarden\ApkHarden.exe` updated. The desktop shortcut already points here.

---

## Done criteria

- `ProxyApplication` uses file-based `DexClassLoader` with a persistent, versionCode-keyed cache; `InMemoryDexClassLoader` and `copyNativeLibraryPaths` are gone.
- `shell.dex` rebuilt at `--min-api 23`.
- Packager JUnit suite green.
- API 29 device: installs, launches with no FATAL, reaches the real app UI; warm-cache launch measurably faster than first launch.
- Emulator results (API 26/28) recorded (pass, or documented ABI limitation).
- README/memory updated; desktop app redeployed.
