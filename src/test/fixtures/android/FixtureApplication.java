package com.apkharden.verification;

import android.app.Application;
import android.util.Log;

public final class FixtureApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Log.i("ApkHardenFixture", "APPLICATION_OK:" + SecretFeature.message());
    }
}
