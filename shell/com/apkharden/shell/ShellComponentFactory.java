package com.apkharden.shell;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;

/** Public API 29+ hook that installs the decrypted in-memory class loader before app creation. */
public final class ShellComponentFactory extends AppComponentFactory {
    private static volatile AppComponentFactory originalFactory;

    @Override
    public ClassLoader instantiateClassLoader(ClassLoader defaultLoader, ApplicationInfo info) {
        try {
            PayloadLoader.LoadedPayload loaded = InMemoryPayloadLoader.load(
                    info.sourceDir,
                    info.nativeLibraryDir,
                    defaultLoader);
            install(loaded);
            return loaded.classLoader;
        } catch (Throwable error) {
            throw new IllegalStateException(
                    "APH-E201 ApkHarden failed to initialize encrypted DEX", error);
        }
    }

    static synchronized void install(PayloadLoader.LoadedPayload loaded) throws Exception {
        ShellRuntime.install(loaded);
        String factoryName = loaded.metadata.originalComponentFactory;
        if (!factoryName.isEmpty() && !factoryName.equals(ShellComponentFactory.class.getName())) {
            Object candidate = loaded.classLoader.loadClass(factoryName).newInstance();
            if (!(candidate instanceof AppComponentFactory)) {
                throw new IllegalStateException("Original AppComponentFactory has the wrong type: " + factoryName);
            }
            originalFactory = (AppComponentFactory) candidate;
        } else {
            originalFactory = null;
        }
    }

    static PayloadLoader.LoadedPayload currentPayload() {
        return ShellRuntime.currentPayload();
    }

    @Override
    public Application instantiateApplication(ClassLoader defaultLoader, String className)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        PayloadLoader.LoadedPayload loaded = ShellRuntime.currentPayload();
        if (loaded == null) {
            // API 28 has AppComponentFactory but not the early class-loader hook. The proxy builds
            // the payload loader from attachBaseContext and installs it before providers launch.
            return super.instantiateApplication(defaultLoader, className);
        }
        String target = className;
        if (ProxyApplication.class.getName().equals(className)) {
            target = loaded.metadata.originalApplication;
            if (target.isEmpty()) target = Application.class.getName();
        }
        return originalFactory != null
                ? originalFactory.instantiateApplication(loaded.classLoader, target)
                : super.instantiateApplication(loaded.classLoader, target);
    }

    @Override
    public Activity instantiateActivity(ClassLoader defaultLoader, String className, Intent intent)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        ClassLoader loader = businessLoader(defaultLoader);
        return originalFactory != null
                ? originalFactory.instantiateActivity(loader, className, intent)
                : super.instantiateActivity(loader, className, intent);
    }

    @Override
    public Service instantiateService(ClassLoader defaultLoader, String className, Intent intent)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        ClassLoader loader = businessLoader(defaultLoader);
        return originalFactory != null
                ? originalFactory.instantiateService(loader, className, intent)
                : super.instantiateService(loader, className, intent);
    }

    @Override
    public BroadcastReceiver instantiateReceiver(ClassLoader defaultLoader, String className, Intent intent)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        ClassLoader loader = businessLoader(defaultLoader);
        return originalFactory != null
                ? originalFactory.instantiateReceiver(loader, className, intent)
                : super.instantiateReceiver(loader, className, intent);
    }

    @Override
    public ContentProvider instantiateProvider(ClassLoader defaultLoader, String className)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        ClassLoader loader = businessLoader(defaultLoader);
        return originalFactory != null
                ? originalFactory.instantiateProvider(loader, className)
                : super.instantiateProvider(loader, className);
    }

    private static ClassLoader businessLoader(ClassLoader fallback) {
        PayloadLoader.LoadedPayload loaded = ShellRuntime.currentPayload();
        return loaded == null ? fallback : loaded.classLoader;
    }
}
