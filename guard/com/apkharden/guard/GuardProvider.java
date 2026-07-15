package com.apkharden.guard;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;

/**
 * Static APK guard used by the upload-only hardening flow.
 *
 * The provider is injected into the final manifest and its dex is appended to the APK. Android
 * loads it through the normal installed APK class path, so this path does not extract writable dex,
 * create a secondary class loader, replace the business Application, or use hidden APIs.
 */
public final class GuardProvider extends ContentProvider {
    private static final String META_SIG_HASH = "com.apkharden.SIG_HASH";

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) return false;

        String expectedHash = readExpectedHash(context);
        if (expectedHash.length() != 64
                || AntiDebug.isDetected(context)
                || !AntiTamper.verify(context, expectedHash)) {
            Process.killProcess(Process.myPid());
            System.exit(0);
            return false;
        }
        return true;
    }

    private static String readExpectedHash(Context context) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(
                    context.getPackageName(),
                    PackageManager.GET_META_DATA);
            Bundle metaData = info.metaData;
            return metaData == null ? "" : metaData.getString(META_SIG_HASH, "");
        } catch (Exception ignored) {
            return "";
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }
}
