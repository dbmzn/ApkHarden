package com.example.fixture.defaultapp;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public final class SmokeActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this);
        view.setText("ApkHarden default Application fixture");
        setContentView(view);
    }
}
