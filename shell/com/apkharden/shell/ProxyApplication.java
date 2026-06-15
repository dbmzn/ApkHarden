package com.apkharden.shell;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Process;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;

public class ProxyApplication extends Application {

    private String realAppName = "";

    @Override
    protected void attachBaseContext(Context base) {
        try {
            Bundle md = readMetaData(base);
            String expectedHash = md.getString(Constants.META_SIG_HASH, "");
            realAppName = md.getString(Constants.META_APP_NAME, "");
            int dexCount = parseDexCount(md);

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

            // InMemoryDexClassLoader has no native library search path, so classes loaded by it
            // would fail System.loadLibrary (e.g. libmmkv.so lives in the APK's lib/<abi>/).
            // Copy the original PathClassLoader's native library paths onto our loader.
            copyNativeLibraryPaths(parent, dexLoader);

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

            // realApp.attach(baseContext) - hidden Application#attach(Context)
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            attach.setAccessible(true);
            attach.invoke(realApp, getBaseContext());

            swapActivityThreadApplication(realApp);

            realApp.onCreate();
        } catch (Throwable t) {
            throw new RuntimeException("ApkHarden: failed to start real application", t);
        }
    }

    // ---- reflection helpers ----

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
            } catch (NoSuchFieldException e) {
                k = k.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    // The packager writes DEX_COUNT into the manifest as a string ("1"), so Bundle.getInt
    // returns the default 0 (logging "expected Integer but value was a java.lang.String").
    // Read it type-tolerantly: a count of 0 means no dexes are decrypted and the in-memory
    // class loader is fed an empty array, which aborts the process natively.
    private int parseDexCount(Bundle md) {
        Object v = md.get(Constants.META_DEX_COUNT);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private Bundle readMetaData(Context base) throws PackageManager.NameNotFoundException {
        ApplicationInfo ai = base.getPackageManager()
                .getApplicationInfo(base.getPackageName(), PackageManager.GET_META_DATA);
        return ai.metaData != null ? ai.metaData : new Bundle();
    }

    private byte[] readAsset(Context base, String path) throws Exception {
        InputStream in = base.getAssets().open(path);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private void kill() {
        Process.killProcess(Process.myPid());
        System.exit(0);
    }
}
