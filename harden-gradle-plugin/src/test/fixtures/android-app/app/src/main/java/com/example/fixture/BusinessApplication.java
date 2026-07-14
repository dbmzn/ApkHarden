package com.example.fixture;

import android.app.Application;
import android.content.Context;

public final class BusinessApplication extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ProbeState.recordApplicationCreate();
    }
}
