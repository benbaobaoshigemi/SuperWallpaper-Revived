package com.miui.superwallpapernoaod;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import android.app.AndroidAppHelper;

/** Shared settings between the module UI and LSPosed-hooked processes. */
final class ModuleSettings {
    static final String PACKAGE_NAME = "com.miui.superwallpapernoaod";
    static final String PREFS_NAME = "module_settings";
    static final String KEY_DISABLE_AOD_LOCK_ZOOM = "disable_aod_lock_zoom";
    static final String KEY_REUSE_OEM_FULL_AOD_DIMMING = "reuse_oem_full_aod_dimming";
    static final String KEY_CONTINUE_AOD_ROTATION = "continue_aod_rotation";
    static final String KEY_APP_TRANSITION_ZOOM = "app_transition_zoom";
    static final String KEY_FORCE_FULL_AOD = "force_full_aod";
    private static final String SECURE_DISABLE_AOD_LOCK_ZOOM = "sw_noaod_disable_aod_lock_zoom";
    private static final String SECURE_REUSE_OEM_FULL_AOD_DIMMING = "sw_noaod_reuse_oem_full_aod_dimming";
    private static final String SECURE_CONTINUE_AOD_ROTATION = "sw_noaod_continue_aod_rotation";
    private static final String SECURE_APP_TRANSITION_ZOOM = "sw_noaod_app_transition_zoom";
    private static final String SECURE_FORCE_FULL_AOD = "sw_noaod_force_full_aod";
    static final String PROVIDER_AUTHORITY = PACKAGE_NAME + ".settings";
    static final Uri PROVIDER_URI = Uri.parse("content://" + PROVIDER_AUTHORITY);

    private static volatile boolean loaded;
    private static boolean disableAodToLockZoom;
    private static boolean reuseOemFullAodDimming = true;
    private static boolean continueAodRotation;
    private static boolean appTransitionZoom = true;
    private static boolean forceFullAod = true;
    private static long loadedAt;
    private static final long CACHE_TTL_MS = 1000L;
    private static boolean observerRegistered;

    private ModuleSettings() {
    }

    static boolean disableAodToLockZoom() {
        refreshSecureValue(0);
        loadFromProvider();
        return disableAodToLockZoom;
    }

    static boolean reuseOemFullAodDimming() {
        refreshSecureValue(1);
        loadFromProvider();
        return reuseOemFullAodDimming;
    }

    static boolean continueAodRotation() {
        refreshSecureValue(2);
        loadFromProvider();
        return continueAodRotation;
    }

    static boolean appTransitionZoom() {
        refreshSecureValue(3);
        loadFromProvider();
        return appTransitionZoom;
    }

    static boolean forceFullAod() {
        refreshSecureValue(4);
        loadFromProvider();
        return forceFullAod;
    }

    private static void refreshSecureValue(int which) {
        Context context = AndroidAppHelper.currentApplication();
        if (context == null) {
            return;
        }
        try {
            String key;
            if (which == 0) {
                key = SECURE_DISABLE_AOD_LOCK_ZOOM;
            } else if (which == 1) {
                key = SECURE_REUSE_OEM_FULL_AOD_DIMMING;
            } else if (which == 2) {
                key = SECURE_CONTINUE_AOD_ROTATION;
            } else if (which == 3) {
                key = SECURE_APP_TRANSITION_ZOOM;
            } else {
                key = SECURE_FORCE_FULL_AOD;
            }
            int value = Settings.Secure.getInt(context.getContentResolver(), key, Integer.MIN_VALUE);
            if (value == Integer.MIN_VALUE) {
                return;
            }
            synchronized (ModuleSettings.class) {
                if (which == 0) {
                    disableAodToLockZoom = value != 0;
                } else if (which == 1) {
                    reuseOemFullAodDimming = value != 0;
                } else if (which == 2) {
                    continueAodRotation = value != 0;
                } else if (which == 3) {
                    appTransitionZoom = value != 0;
                } else {
                    forceFullAod = value != 0;
                }
                loaded = true;
                loadedAt = SystemClock.elapsedRealtime();
            }
        } catch (RuntimeException ignored) {
            // The cached/provider value remains available during early process startup.
        }
    }

    /**
     * XSharedPreferences needs a world-readable XML file. Modern Android keeps this app's
     * preferences private, so SystemUI would silently fall back to defaults. Read through this
     * module's exported, read-only provider instead. Settings take effect after scope restart.
     */
    private static synchronized void loadFromProvider() {
        if (loaded && SystemClock.elapsedRealtime() - loadedAt < CACHE_TTL_MS) {
            return;
        }
        Context context = AndroidAppHelper.currentApplication();
        if (context == null) {
            return;
        }
        try {
            int disable = Settings.Secure.getInt(context.getContentResolver(),
                    SECURE_DISABLE_AOD_LOCK_ZOOM, Integer.MIN_VALUE);
            int dimming = Settings.Secure.getInt(context.getContentResolver(),
                    SECURE_REUSE_OEM_FULL_AOD_DIMMING, Integer.MIN_VALUE);
            int rotation = Settings.Secure.getInt(context.getContentResolver(),
                    SECURE_CONTINUE_AOD_ROTATION, Integer.MIN_VALUE);
            int appZoom = Settings.Secure.getInt(context.getContentResolver(),
                    SECURE_APP_TRANSITION_ZOOM, Integer.MIN_VALUE);
            int fullAod = Settings.Secure.getInt(context.getContentResolver(),
                    SECURE_FORCE_FULL_AOD, Integer.MIN_VALUE);
            if (disable != Integer.MIN_VALUE && dimming != Integer.MIN_VALUE
                    && rotation != Integer.MIN_VALUE && appZoom != Integer.MIN_VALUE
                    && fullAod != Integer.MIN_VALUE) {
                disableAodToLockZoom = disable != 0;
                reuseOemFullAodDimming = dimming != 0;
                continueAodRotation = rotation != 0;
                appTransitionZoom = appZoom != 0;
                forceFullAod = fullAod != 0;
            } else {
                Bundle values = context.getContentResolver().call(PROVIDER_URI,
                        ModuleSettingsProvider.METHOD_GET_SETTINGS, null, null);
                if (values == null) {
                    return;
                }
                disableAodToLockZoom = values.getBoolean(KEY_DISABLE_AOD_LOCK_ZOOM, false);
                reuseOemFullAodDimming = values.getBoolean(KEY_REUSE_OEM_FULL_AOD_DIMMING, true);
                continueAodRotation = values.getBoolean(KEY_CONTINUE_AOD_ROTATION, false);
                appTransitionZoom = values.getBoolean(KEY_APP_TRANSITION_ZOOM, true);
                forceFullAod = values.getBoolean(KEY_FORCE_FULL_AOD, true);
            }
            loaded = true;
            loadedAt = SystemClock.elapsedRealtime();
            try {
                registerObserver(context);
            } catch (RuntimeException observerError) {
                observerRegistered = false;
                Log.w("SWNoAOD", "secure settings observer registration deferred pid="
                        + Process.myPid(), observerError);
            }
            Log.i("SWNoAOD", "settings refreshed pid=" + Process.myPid()
                    + " disableZoom=" + disableAodToLockZoom
                    + " reuseDimming=" + reuseOemFullAodDimming
                    + " continueRotation=" + continueAodRotation
                    + " forceFullAod=" + forceFullAod);
        } catch (Exception e) {
            // SystemUI can call this before the module provider is available. Retry next time.
            Log.w("SWNoAOD", "settings refresh failed pid=" + Process.myPid(), e);
        }
    }

    private static void registerObserver(Context context) {
        if (observerRegistered) {
            return;
        }
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override
                    public void onChange(boolean selfChange, Uri uri) {
                        synchronized (ModuleSettings.class) {
                            loaded = false;
                            loadedAt = 0L;
                        }
                    }
                };
        context.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(SECURE_DISABLE_AOD_LOCK_ZOOM), false, observer);
        context.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(SECURE_REUSE_OEM_FULL_AOD_DIMMING), false, observer);
        context.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(SECURE_CONTINUE_AOD_ROTATION), false, observer);
        context.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(SECURE_APP_TRANSITION_ZOOM), false, observer);
        context.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(SECURE_FORCE_FULL_AOD), false, observer);
        observerRegistered = true;
    }

    static void save(Context context, String key, boolean value) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        preferences.edit().putBoolean(key, value).commit();
        context.getContentResolver().notifyChange(PROVIDER_URI, null);
        writeSecureMirror(key, value);
    }

    static void syncSecureSettings(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String command = "settings put secure " + SECURE_DISABLE_AOD_LOCK_ZOOM + " "
                + boolValue(preferences.getBoolean(KEY_DISABLE_AOD_LOCK_ZOOM, false))
                + "; settings put secure " + SECURE_REUSE_OEM_FULL_AOD_DIMMING + " "
                + boolValue(preferences.getBoolean(KEY_REUSE_OEM_FULL_AOD_DIMMING, true))
                + "; settings put secure " + SECURE_CONTINUE_AOD_ROTATION + " "
                + boolValue(preferences.getBoolean(KEY_CONTINUE_AOD_ROTATION, false))
                + "; settings put secure " + SECURE_APP_TRANSITION_ZOOM + " "
                + boolValue(preferences.getBoolean(KEY_APP_TRANSITION_ZOOM, true))
                + "; settings put secure " + SECURE_FORCE_FULL_AOD + " "
                + boolValue(preferences.getBoolean(KEY_FORCE_FULL_AOD, true));
        executeSecureCommand(command, "all settings");
    }

    private static void writeSecureMirror(String key, boolean value) {
        String secureKey;
        if (KEY_DISABLE_AOD_LOCK_ZOOM.equals(key)) {
            secureKey = SECURE_DISABLE_AOD_LOCK_ZOOM;
        } else if (KEY_REUSE_OEM_FULL_AOD_DIMMING.equals(key)) {
            secureKey = SECURE_REUSE_OEM_FULL_AOD_DIMMING;
        } else if (KEY_CONTINUE_AOD_ROTATION.equals(key)) {
            secureKey = SECURE_CONTINUE_AOD_ROTATION;
        } else if (KEY_APP_TRANSITION_ZOOM.equals(key)) {
            secureKey = SECURE_APP_TRANSITION_ZOOM;
        } else if (KEY_FORCE_FULL_AOD.equals(key)) {
            secureKey = SECURE_FORCE_FULL_AOD;
        } else {
            return;
        }
        executeSecureCommand("settings put secure " + secureKey + " " + boolValue(value), secureKey);
    }

    private static String boolValue(boolean value) {
        return value ? "1" : "0";
    }

    private static void executeSecureCommand(String command, String label) {
        try {
            java.lang.Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            if (process.waitFor() != 0) {
                Log.w("SWNoAOD", "secure settings mirror failed key=" + label);
            }
        } catch (Exception e) {
            Log.w("SWNoAOD", "secure settings mirror unavailable key=" + label, e);
        }
    }
}
