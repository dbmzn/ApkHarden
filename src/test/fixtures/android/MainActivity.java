package com.apkharden.verification;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String message = "APK HARDEN " + SecretFeature.message();
        TextView view = new TextView(this);
        view.setText(message);
        view.setTextSize(28f);
        view.setPadding(48, 96, 48, 48);
        setContentView(view);
        Log.i("ApkHardenFixture", "ACTIVITY_OK:" + message);
    }
}
