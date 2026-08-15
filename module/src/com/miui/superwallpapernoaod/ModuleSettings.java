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
    static final String KEY_NON_FULLSCREEN_AOD_TRANSITION = "non_fullscreen_aod_transition";
    static final String KEY_CLASSIC_SUPER_WALLPAPER_AOD = "classic_super_wallpaper_aod";
    static final String KEY_DESKTOP_FOLLOW_SCALE = "desktop_follow_scale";
    static final String KEY_DESKTOP_FOLLOW_DAMPING = "desktop_follow_damping";
    static final String KEY_DESKTOP_FOLLOW_RESPONSE = "desktop_follow_response";
    private static final String SECURE_DISABLE_AOD_LOCK_ZOOM = "sw_noaod_disable_aod_lock_zoom";
    private static final String SECURE_REUSE_OEM_FULL_AOD_DIMMING = "sw_noaod_reuse_oem_full_aod_dimming";
    private static final String SECURE_CONTINUE_AOD_ROTATION = "sw_noaod_continue_aod_rotation";
    private static final String SECURE_APP_TRANSITION_ZOOM = "sw_noaod_app_transition_zoom";
    private static final String SECURE_FORCE_FULL_AOD = "sw_noaod_force_full_aod";
    private static final String SECURE_NON_FULLSCREEN_AOD_TRANSITION =
            "sw_noaod_non_fullscreen_aod_transition";
    private static final String SECURE_CLASSIC_SUPER_WALLPAPER_AOD =
            "sw_noaod_classic_super_wallpaper_aod";
    private static final String SECURE_DESKTOP_FOLLOW_SCALE = "sw_noaod_desktop_follow_scale";
    private static final String SECURE_DESKTOP_FOLLOW_DAMPING = "sw_noaod_desktop_follow_damping";
    private static final String SECURE_DESKTOP_FOLLOW_RESPONSE = "sw_noaod_desktop_follow_response";
    static final String PROVIDER_AUTHORITY = PACKAGE_NAME + ".settings";
    static final Uri PROVIDER_URI = Uri.parse("content://" + PROVIDER_AUTHORITY);

    private static volatile boolean loaded;
    private static boolean disableAodToLockZoom;
    private static boolean reuseOemFullAodDimming = true;
    private static boolean continueAodRotation;
    private static boolean appTransitionZoom = true;
    private static boolean forceFullAod = true;
    private static boolean nonFullscreenAodTransition = true;
    private static boolean classicSuperWallpaperAod;
    private static int desktopFollowScale = 100;
    private static int desktopFollowDamping = 100;
    private static int desktopFollowResponse = 100;
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

    static boolean nonFullscreenAodTransition() {
        refreshSecureValue(5);
        loadFromProvider();
        return nonFullscreenAodTransition;
    }

    static boolean classicSuperWallpaperAod() {
        refreshSecureValue(7);
        loadFromProvider();
        return classicSuperWallpaperAod;
    }

    static int desktopFollowScale() {
        refreshSecureValue(6);
        loadFromProvider();
        return desktopFollowScale;
    }

    static int desktopFollowDamping() {
        refreshSecureValue(8);
        loadFromProvider();
        return desktopFollowDamping;
    }

    static int desktopFollowResponse() {
        refreshSecureValue(9);
        loadFromProvider();
        return desktopFollowResponse;
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
            } else if (which == 4) {
                key = SECURE_FORCE_FULL_AOD;
            } else if (which == 5) {
                key = SECURE_NON_FULLSCREEN_AOD_TRANSITION;
            } else if (which == 6) {
                key = SECURE_DESKTOP_FOLLOW_SCALE;
            } else if (which == 7) {
                key = SECURE_CLASSIC_SUPER_WALLPAPER_AOD;
            } else if (which == 8) {
                key = SECURE_DESKTOP_FOLLOW_DAMPING;
            } else {
                key = SECURE_DESKTOP_FOLLOW_RESPONSE;
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
                } else if (which == 4) {
                    forceFullAod = value != 0;
                } else if (which == 5) {
                    nonFullscreenAodTransition = value != 0;
                } else if (which == 6) {
                    desktopFollowScale = boundFollowValue(value);
                } else if (which == 7) {
                    classicSuperWallpaperAod = value != 0;
                } else if (which == 8) {
                    desktopFollowDamping = boundFollowValue(value);
                } else {
                    desktopFollowResponse = boundFollowValue(value);
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
            int localAodTransition = Settings.Secure.getInt(context.getContentResolver(),
                    SECURE_NON_FULLSCREEN_AOD_TRANSITION, Integer.MIN_VALUE);
            int classicAod = Settings.Secure.getInt(context.getContentResolver(),
                    SECURE_CLASSIC_SUPER_WALLPAPER_AOD, Integer.MIN_VALUE);
            int followScale = Settings.Secure.getInt(context.getContentResolver(),
                    SECURE_DESKTOP_FOLLOW_SCALE, Integer.MIN_VALUE);
            int followDamping = Settings.Secure.getInt(context.getContentResolver(),
                    SECURE_DESKTOP_FOLLOW_DAMPING, Integer.MIN_VALUE);
            int followResponse = Settings.Secure.getInt(context.getContentResolver(),
                    SECURE_DESKTOP_FOLLOW_RESPONSE, Integer.MIN_VALUE);
            if (disable != Integer.MIN_VALUE && dimming != Integer.MIN_VALUE
                    && rotation != Integer.MIN_VALUE && appZoom != Integer.MIN_VALUE
                    && fullAod != Integer.MIN_VALUE && localAodTransition != Integer.MIN_VALUE
                    && classicAod != Integer.MIN_VALUE && followScale != Integer.MIN_VALUE
                    && followDamping != Integer.MIN_VALUE && followResponse != Integer.MIN_VALUE) {
                disableAodToLockZoom = disable != 0;
                reuseOemFullAodDimming = dimming != 0;
                continueAodRotation = rotation != 0;
                appTransitionZoom = appZoom != 0;
                forceFullAod = fullAod != 0;
                nonFullscreenAodTransition = localAodTransition != 0;
                classicSuperWallpaperAod = classicAod != 0;
                desktopFollowScale = boundFollowValue(followScale);
                desktopFollowDamping = boundFollowValue(followDamping);
                desktopFollowResponse = boundFollowValue(followResponse);
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
                nonFullscreenAodTransition = values.getBoolean(
                        KEY_NON_FULLSCREEN_AOD_TRANSITION, true);
                classicSuperWallpaperAod = values.getBoolean(
                        KEY_CLASSIC_SUPER_WALLPAPER_AOD, false);
                desktopFollowScale = boundFollowValue(
                        values.getInt(KEY_DESKTOP_FOLLOW_SCALE, 100));
                desktopFollowDamping = boundFollowValue(
                        values.getInt(KEY_DESKTOP_FOLLOW_DAMPING, 100));
                desktopFollowResponse = boundFollowValue(
                        values.getInt(KEY_DESKTOP_FOLLOW_RESPONSE, 100));
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
                    + " forceFullAod=" + forceFullAod
                    + " localAodTransition=" + nonFullscreenAodTransition
                    + " classicAod=" + classicSuperWallpaperAod
                    + " followScale=" + desktopFollowScale
                    + " followDamping=" + desktopFollowDamping
                    + " followResponse=" + desktopFollowResponse);
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
        context.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(SECURE_NON_FULLSCREEN_AOD_TRANSITION), false, observer);
        context.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(SECURE_CLASSIC_SUPER_WALLPAPER_AOD), false, observer);
        context.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(SECURE_DESKTOP_FOLLOW_SCALE), false, observer);
        context.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(SECURE_DESKTOP_FOLLOW_DAMPING), false, observer);
        context.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(SECURE_DESKTOP_FOLLOW_RESPONSE), false, observer);
        observerRegistered = true;
    }

    static void save(Context context, String key, boolean value) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        preferences.edit().putBoolean(key, value).commit();
        context.getContentResolver().notifyChange(PROVIDER_URI, null);
        writeSecureMirror(key, value);
    }

    static void save(Context context, String key, int value) {
        if (!KEY_DESKTOP_FOLLOW_SCALE.equals(key)
                && !KEY_DESKTOP_FOLLOW_DAMPING.equals(key)
                && !KEY_DESKTOP_FOLLOW_RESPONSE.equals(key)) {
            return;
        }
        int boundedValue = boundFollowValue(value);
        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        preferences.edit().putInt(key, boundedValue).commit();
        context.getContentResolver().notifyChange(PROVIDER_URI, null);
        String secureKey = KEY_DESKTOP_FOLLOW_SCALE.equals(key)
                ? SECURE_DESKTOP_FOLLOW_SCALE
                : KEY_DESKTOP_FOLLOW_DAMPING.equals(key)
                ? SECURE_DESKTOP_FOLLOW_DAMPING : SECURE_DESKTOP_FOLLOW_RESPONSE;
        executeSecureCommand("settings put secure " + secureKey + " " + boundedValue, secureKey);
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
                + boolValue(preferences.getBoolean(KEY_FORCE_FULL_AOD, true))
                + "; settings put secure " + SECURE_NON_FULLSCREEN_AOD_TRANSITION + " "
                + boolValue(preferences.getBoolean(KEY_NON_FULLSCREEN_AOD_TRANSITION, true))
                + "; settings put secure " + SECURE_CLASSIC_SUPER_WALLPAPER_AOD + " "
                + boolValue(preferences.getBoolean(KEY_CLASSIC_SUPER_WALLPAPER_AOD, false))
                + followSyncCommand(preferences, KEY_DESKTOP_FOLLOW_SCALE,
                SECURE_DESKTOP_FOLLOW_SCALE)
                + followSyncCommand(preferences, KEY_DESKTOP_FOLLOW_DAMPING,
                SECURE_DESKTOP_FOLLOW_DAMPING)
                + followSyncCommand(preferences, KEY_DESKTOP_FOLLOW_RESPONSE,
                SECURE_DESKTOP_FOLLOW_RESPONSE);
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
        } else if (KEY_NON_FULLSCREEN_AOD_TRANSITION.equals(key)) {
            secureKey = SECURE_NON_FULLSCREEN_AOD_TRANSITION;
        } else if (KEY_CLASSIC_SUPER_WALLPAPER_AOD.equals(key)) {
            secureKey = SECURE_CLASSIC_SUPER_WALLPAPER_AOD;
        } else {
            return;
        }
        executeSecureCommand("settings put secure " + secureKey + " " + boolValue(value), secureKey);
    }

    private static String boolValue(boolean value) {
        return value ? "1" : "0";
    }

    private static int boundFollowValue(int value) {
        return Math.max(0, Math.min(400, value));
    }

    private static String followSyncCommand(
            SharedPreferences preferences, String key, String secureKey) {
        return "; settings put secure " + secureKey + " "
                + boundFollowValue(preferences.getInt(key, 100));
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
