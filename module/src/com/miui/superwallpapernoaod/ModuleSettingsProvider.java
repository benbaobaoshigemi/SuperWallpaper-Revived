package com.miui.superwallpapernoaod;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Read-only IPC bridge for settings consumed by LSPosed-hooked processes. */
public final class ModuleSettingsProvider extends ContentProvider {
    static final String METHOD_GET_SETTINGS = "get_settings";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD_GET_SETTINGS.equals(method) || getContext() == null) {
            return super.call(method, arg, extras);
        }
        SharedPreferences preferences = getContext().getSharedPreferences(
                ModuleSettings.PREFS_NAME, 0);
        Bundle values = new Bundle();
        values.putBoolean(ModuleSettings.KEY_DISABLE_AOD_LOCK_ZOOM,
                preferences.getBoolean(ModuleSettings.KEY_DISABLE_AOD_LOCK_ZOOM, false));
        values.putBoolean(ModuleSettings.KEY_REUSE_OEM_FULL_AOD_DIMMING,
                preferences.getBoolean(ModuleSettings.KEY_REUSE_OEM_FULL_AOD_DIMMING, true));
        values.putBoolean(ModuleSettings.KEY_CONTINUE_AOD_ROTATION,
                preferences.getBoolean(ModuleSettings.KEY_CONTINUE_AOD_ROTATION, false));
        values.putBoolean(ModuleSettings.KEY_APP_TRANSITION_ZOOM,
                preferences.getBoolean(ModuleSettings.KEY_APP_TRANSITION_ZOOM, true));
        values.putBoolean(ModuleSettings.KEY_FORCE_FULL_AOD,
                preferences.getBoolean(ModuleSettings.KEY_FORCE_FULL_AOD, true));
        return values;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
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
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
