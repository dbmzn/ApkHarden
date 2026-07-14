package com.example.fixture.defaultapp;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import java.io.File;

public class ProbeProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String argument, Bundle extras) {
        Bundle result = new Bundle();
        result.putInt("pid", Process.myPid());
        result.putString("process", processName());
        return result;
    }
    private String processName() {
        try {
            return new String(java.nio.file.Files.readAllBytes(new File("/proc/self/cmdline").toPath()))
                .replace("\u0000", "")
                .trim();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
