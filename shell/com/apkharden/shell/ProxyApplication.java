package com.apkharden.shell;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;

import com.apkharden.guard.AntiDebug;
import com.apkharden.guard.AntiTamper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Compatibility entry point for API 23-28. API 29+ starts the real Application via the factory. */
public final class ProxyApplication extends Application {
    private static final String META_SIG_HASH = "com.apkharden.SIG_HASH";
    private static final String META_ORIGINAL_APPLICATION = "com.apkharden.ORIGINAL_APPLICATION";

    private PayloadLoader.LoadedPayload loaded;
    private String originalApplication = "";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            Bundle metadata = applicationMetadata(base);
            String expectedSignature = metadata.getString(META_SIG_HASH, "");
            if (expectedSignature.length() != 64
                    || AntiDebug.isDetected(base)
                    || !AntiTamper.verify(base, expectedSignature)) {
                failClosed();
                return;
            }
            originalApplication = metadata.getString(META_ORIGINAL_APPLICATION, "");
            loaded = ShellRuntime.currentPayload();
            if (loaded == null) {
                ApplicationInfo info = base.getApplicationInfo();
                loaded = PayloadLoader.loadLegacy(
                        info.sourceDir,
                        info.dataDir,
                        info.nativeLibraryDir,
                        base.getClassLoader());
                ShellRuntime.install(loaded);
                if (Build.VERSION.SDK_INT == 28) installApi28Factory(loaded);
            }
            Thread.currentThread().setContextClassLoader(loaded.classLoader);

            // AppComponentFactory handles API 28 components. API 23-27 need the package loader
            // replaced before the framework creates providers, activities, receivers or services.
            if (Build.VERSION.SDK_INT <= 27) replaceLoadedApkClassLoader(base, loaded.classLoader);
        } catch (Throwable error) {
            failClosed();
        }
    }

    // Isolate the API-28-only AppComponentFactory subclass behind reflection. A direct type
    // reference makes the API 26 verifier resolve its missing android.app.AppComponentFactory
    // superclass before the SDK branch is evaluated.
    private void installApi28Factory(PayloadLoader.LoadedPayload payload) throws Exception {
        Class<?> factory = Class.forName("com.apkharden.shell.ShellComponentFactory");
        Method install = factory.getDeclaredMethod("install", PayloadLoader.LoadedPayload.class);
        install.setAccessible(true);
        install.invoke(null, payload);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (originalApplication.isEmpty()) return;
        try {
            Application real = (Application) loaded.classLoader
                    .loadClass(originalApplication)
                    .newInstance();
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            attach.setAccessible(true);
            attach.invoke(real, getBaseContext());
            swapApplication(real);
            real.onCreate();
        } catch (Throwable error) {
            throw new IllegalStateException("ApkHarden failed to start the original Application", error);
        }
    }

    private void replaceLoadedApkClassLoader(Context base, ClassLoader loader) throws Exception {
        Object loadedApk = field(base.getClass(), "mPackageInfo").get(base);
        field(loadedApk.getClass(), "mClassLoader").set(loadedApk, loader);
    }

    @SuppressWarnings("unchecked")
    private void swapApplication(Application real) throws Exception {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Object activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
        field(activityThreadClass, "mInitialApplication").set(activityThread, real);

        Object allApplications = field(activityThreadClass, "mAllApplications").get(activityThread);
        if (allApplications instanceof List) {
            List<Application> apps = (List<Application>) allApplications;
            apps.remove(this);
            if (!apps.contains(real)) apps.add(real);
        }
        Object loadedApk = field(getBaseContext().getClass(), "mPackageInfo").get(getBaseContext());
        field(loadedApk.getClass(), "mApplication").set(loadedApk, real);
        transferCallbacks(real);
    }

    @SuppressWarnings("unchecked")
    private void transferCallbacks(Application real) {
        try {
            Object callbacks = field(Application.class, "mActivityLifecycleCallbacks").get(this);
            if (callbacks instanceof List) {
                for (Object callback : new ArrayList<Object>((List<Object>) callbacks)) {
                    real.registerActivityLifecycleCallbacks((ActivityLifecycleCallbacks) callback);
                }
            }
        } catch (Throwable ignored) {}
        try {
            Object callbacks = field(Application.class, "mComponentCallbacks").get(this);
            if (callbacks instanceof List) {
                for (Object callback : new ArrayList<Object>((List<Object>) callbacks)) {
                    real.registerComponentCallbacks((android.content.ComponentCallbacks) callback);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private Bundle applicationMetadata(Context context) throws PackageManager.NameNotFoundException {
        ApplicationInfo info = context.getPackageManager().getApplicationInfo(
                context.getPackageName(), PackageManager.GET_META_DATA);
        return info.metaData == null ? new Bundle() : info.metaData;
    }

    private static void failClosed() {
        Process.killProcess(Process.myPid());
        System.exit(0);
    }
}
