package com.apkharden.runtime;

import static org.junit.Assert.assertEquals;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class MultiProcessRuntimeTest {
    @Test
    public void runtimeInitializesExactlyOnceInMainAndWorkerProcesses() {
        Context context = ApplicationProvider.getApplicationContext();
        ContentResolver resolver = context.getContentResolver();

        Bundle mainFirst = resolver.call(
            Uri.parse("content://com.apkharden.runtime.test.main-probe"),
            "probe",
            null,
            null
        );
        Bundle mainSecond = resolver.call(
            Uri.parse("content://com.apkharden.runtime.test.main-probe"),
            "probe",
            null,
            null
        );
        Bundle workerFirst = resolver.call(
            Uri.parse("content://com.apkharden.runtime.test.worker-probe"),
            "probe",
            null,
            null
        );
        Bundle workerSecond = resolver.call(
            Uri.parse("content://com.apkharden.runtime.test.worker-probe"),
            "probe",
            null,
            null
        );

        assertEquals(1, mainFirst.getInt("installCount"));
        assertEquals(1, mainSecond.getInt("installCount"));
        assertEquals(1, workerFirst.getInt("installCount"));
        assertEquals(1, workerSecond.getInt("installCount"));
    }
}
