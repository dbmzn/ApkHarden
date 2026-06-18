package com.apkharden.shell;

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

public class ProxyApplication extends Application {

    private String realAppName = "";

    @Override
    protected void attachBaseContext(Context base) {
        try {
            // Lift the non-SDK (hidden API) reflection restrictions added in Android 9+ and
            // tightened every release. We reflect ActivityThread / LoadedApk / Application internals
            // below; on Android 12/13/14 with a high targetSdk some of those fields are blocklisted
            // and would throw or return null. This is a one-shot, negligible-cost unseal at startup.
            unsealHiddenApi();

            Bundle md = readMetaData(base);
            String expectedHash = md.getString(Constants.META_SIG_HASH, "");
            realAppName = md.getString(Constants.META_APP_NAME, "");
            int dexCount = parseDexCount(md);

            // 1. Security checks (fail-closed).
            if (AntiDebug.isDetected(base) || !AntiTamper.verify(base, expectedHash)) {
                kill();
                return;
            }

            // 2. Decrypt original dexes to an app-private, version-keyed cache. Persisting them
            //    (instead of holding them in memory) lets ART build and reuse an oat file, so the
            //    real code runs AOT-compiled — near-original performance. Re-decrypt only when a
            //    cache file is missing or invalid (e.g. first launch, or after an app update where
            //    the versionCode — and thus the cache dir — changes).
            File cacheDir = base.getDir("apkharden_" + versionCode(base), Context.MODE_PRIVATE);
            java.util.ArrayList<File> dexFiles = new java.util.ArrayList<File>();
            // Serialize decryption across processes. A multi-process app (push/customer-service/
            // webview cores) can cold-start several processes at once, all racing to write the same
            // cN.dex. An exclusive cross-process file lock lets exactly one process decrypt while the
            // others block, then reuse the finished files — without it, interleaved writes can yield
            // a corrupt dex that isValidDex's magic-only check won't catch, causing rare cold-start
            // crashes that only reproduce when several processes launch simultaneously.
            File lockFile = new File(cacheDir, ".lock");
            FileOutputStream lockFos = new FileOutputStream(lockFile);
            java.nio.channels.FileLock lock = lockFos.getChannel().lock();
            try {
                for (int i = 0; i < dexCount; i++) {
                    File out = new File(cacheDir, "c" + i + ".dex");
                    if (!isValidDex(out)) {
                        byte[] plain = DexDecryptor.decrypt(readAsset(base, Constants.ENC_DIR + "/" + i));
                        atomicWrite(out, plain);
                    }
                    dexFiles.add(out);
                }
            } finally {
                lock.release();
                lockFos.close();
            }

            // 3. Merge the decrypted dexes into the HOST PathClassLoader rather than loading them
            //    in a child DexClassLoader. Keeping every dex in one loader — the shell's
            //    classes.dex, any plain (install-AOT'd) app dexes left in base.apk under selective
            //    hardening, and the decrypted ones — means class references resolve in every
            //    direction. A child loader would ClassNotFound whenever a plain/host class references
            //    a decrypted/child class (parent can't see child). It also preserves the host
            //    loader's identity, native-library search path, and class-loader-context, so we no
            //    longer need to copy native paths or swap LoadedApk.mClassLoader.
            File oatDir = new File(cacheDir, "oat");
            oatDir.mkdirs();
            ClassLoader host = base.getClassLoader();
            if (!dexFiles.isEmpty()) {
                mergeDexIntoHost(host, dexFiles, oatDir);
            }

            // Align the thread-context classloader (some serializers / ServiceLoader / DI frameworks
            // resolve via Thread.getContextClassLoader(), often on background threads).
            Thread.currentThread().setContextClassLoader(host);

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

    // Exempt all hidden APIs for this process via meta-reflection ("double reflection"): we look up
    // getDeclaredMethod through reflection so the *caller* of the restricted lookups appears to be a
    // bootclasspath class (Class), which is exempt from the non-SDK check. Then VMRuntime
    // .setHiddenApiExemptions("L") whitelists every signature prefix. Best-effort and fail-open:
    // pre-P has no restriction, and where it can't be lifted the greylisted fields still resolve.
    private void unsealHiddenApi() {
        try {
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod = Class.class.getDeclaredMethod(
                    "getDeclaredMethod", String.class, Class[].class);
            Class<?> vmRuntime = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclaredMethod.invoke(
                    vmRuntime, "getRuntime", null);
            Method setExemptions = (Method) getDeclaredMethod.invoke(
                    vmRuntime, "setHiddenApiExemptions", new Class[]{String[].class});
            Object runtime = getRuntime.invoke(null);
            setExemptions.invoke(runtime, new Object[]{new String[]{"L"}});
        } catch (Throwable ignored) {
            // pre-Android-9, or restriction can't be lifted on this ROM; reflection below still
            // works for greylisted members, so don't fail the process here.
        }
    }

    // ---- reflection helpers ----

    // Appends the decrypted dexes to the host PathClassLoader via DexPathList.makeDexElements,
    // registering them DIRECTLY to the host. A throwaway DexClassLoader can't be used to build the
    // Element[] because ART forbids a dex file being registered to two class loaders
    // (InternalError: "Attempt to register dex file ... with multiple class loaders"). One loader
    // for shell + any plain (install-AOT'd) app dexes + decrypted dexes keeps class references
    // resolvable in every direction. The makeDexElements/makePathElements signature has churned
    // across API levels (ArrayList vs List params, ±ClassLoader, ±boolean isTrusted), so instead of
    // guessing exact signatures we locate the method by name and bind arguments by parameter type.
    private void mergeDexIntoHost(ClassLoader host, java.util.List<File> dexFiles, File oatDir)
            throws Exception {
        Object hostList = field(host.getClass(), "pathList").get(host);   // BaseDexClassLoader.pathList
        Field elementsField = field(hostList.getClass(), "dexElements");  // DexPathList.dexElements
        Object oldElems = elementsField.get(hostList);

        java.util.ArrayList<File> files = new java.util.ArrayList<File>(dexFiles);
        java.util.ArrayList<Object> suppressed = new java.util.ArrayList<Object>();

        Method make = null;
        for (Method m : hostList.getClass().getDeclaredMethods()) {
            String n = m.getName();
            if (n.equals("makeDexElements") || n.equals("makePathElements")) { make = m; break; }
        }
        if (make == null) throw new NoSuchMethodException("makeDexElements/makePathElements");
        make.setAccessible(true);

        // Bind by parameter type: File→oat, ClassLoader→host, boolean→isTrusted(true),
        // first list→files, second list→suppressed.
        Class<?>[] pt = make.getParameterTypes();
        Object[] args = new Object[pt.length];
        boolean firstList = true;
        for (int i = 0; i < pt.length; i++) {
            Class<?> p = pt[i];
            if (p == File.class) args[i] = oatDir;
            else if (p == ClassLoader.class) args[i] = host;
            else if (p == boolean.class || p == Boolean.class) args[i] = Boolean.TRUE; // isTrusted
            else if (p.isAssignableFrom(java.util.ArrayList.class)) {
                args[i] = firstList ? files : suppressed;
                firstList = false;
            }
        }
        Object newElems = make.invoke(null, args);

        int on = java.lang.reflect.Array.getLength(oldElems);
        int nn = java.lang.reflect.Array.getLength(newElems);
        Object merged = java.lang.reflect.Array.newInstance(
                oldElems.getClass().getComponentType(), on + nn);
        System.arraycopy(oldElems, 0, merged, 0, on);
        System.arraycopy(newElems, 0, merged, on, nn);
        elementsField.set(hostList, merged);
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

        // Re-home callbacks registered on THIS proxy before the swap. Auto-init ContentProviders
        // (e.g. blankj AndroidUtilCode's UtilsFileProvider, AndroidX Startup, analytics SDKs) run
        // between attachBaseContext and onCreate and register their ActivityLifecycleCallbacks on
        // the then-current Application — the proxy. After the swap the framework dispatches every
        // activity/component event to realApp, so those callbacks would silently stop firing
        // (symptom: ActivityUtils.getTopActivity() goes stale → click handlers that resolve the top
        // activity become no-ops once the activity stack is rebuilt). Move them onto realApp.
        transferRegisteredCallbacks(realApp);
    }

    @SuppressWarnings("unchecked")
    private void transferRegisteredCallbacks(Application realApp) {
        try {
            Object cbs = field(Application.class, "mActivityLifecycleCallbacks").get(this);
            if (cbs instanceof List) {
                for (Object cb : new java.util.ArrayList<Object>((List<Object>) cbs)) {
                    realApp.registerActivityLifecycleCallbacks(
                            (Application.ActivityLifecycleCallbacks) cb);
                }
            }
        } catch (Throwable ignored) {
            // field set varies across Android versions; best-effort
        }
        try {
            Object cbs = field(Application.class, "mComponentCallbacks").get(this);
            if (cbs instanceof List) {
                for (Object cb : new java.util.ArrayList<Object>((List<Object>) cbs)) {
                    realApp.registerComponentCallbacks((android.content.ComponentCallbacks) cb);
                }
            }
        } catch (Throwable ignored) {
            // best-effort
        }
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
        File tmp = new File(out.getAbsolutePath() + "." + Process.myPid() + ".tmp");
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
