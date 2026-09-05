package com.apkharden.verification;

import android.app.AppComponentFactory;
import android.app.Application;
import android.util.Log;

public final class FixtureComponentFactory extends AppComponentFactory {
    @Override public Application instantiateApplication(ClassLoader loader, String className)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        Log.i("ApkHardenFixture", "ORIGINAL_FACTORY_OK");
        return super.instantiateApplication(loader, className);
    }
}
