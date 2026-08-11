package com.miui.superwallpapernoaod;

import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 超级壁纸保留主题AOD (SW NoAOD)
 *
 * 目标：应用超级壁纸后，锁屏->桌面 3D 动效保持原样；
 * AOD 完全交给主题/息屏(com.miui.aod)，超级壁纸引擎不再渲染 AOD 动画与时钟。
 *
 * 原理（基于原生代码分析）：
 * 1. thememanager 应用超级壁纸后通过 AodUtils.h / WallpaperUtils.y9n 发送
 *    SUPER_WALLPAPER_APPLY_STATE_CHANGED 广播，com.miui.aod 收到后把
 *    secure key "aod_using_super_wallpaper" 置 1 并切换 AOD 样式 -> 屏蔽这两个广播。
 * 2. 场景引擎只在 aod_using_super_wallpaper==1 时进入 AOD 状态
 *    (onAmbientModeChanged / onGoingToSleep / ForceAOD 命令)，并且
 *    com.miui.aod/systemui 可能直接下发 action_aod 等命令 ->
 *    强制 isAodUsingSuperWallpaperStyle=false、阻断 AOD 入口方法、吞掉 AOD 命令。
 * 3. 主应用 MiuiWallpaperManagerService.J 会把应用状态广播给 systemui -> 一并屏蔽。
 *
 * 全屏AOD（“和锁屏样式一致”）补充：
 * - aurora（小米14 Ultra）device feature 无 support_aod_fullscreen，com.miui.aod 的
 *   SUPPORT_FULL_AOD=false 且 systemui 的 MiuiConfigs.SUPPORT_FULL_AOD=false，
 *   设置页“息屏样式”整个容器被隐藏 -> 强制 com.miui.aod.Utils.isSupportFsAod()/isFullAodSupport()=true，
 *   并在 systemui 把 MiuiConfigs.SUPPORT_FULL_AOD 置 true。
 * - 锁屏被超级壁纸占用时 systemui 的 MiuiFullAodManager.onWallpaperChanged() 会把
 *   isWallpaperTypeSupport 置 false（多出 super_wallpaper 排除项），随后 updateSettings()
 *   写 full_screen_aod_support=0 / full_screen_aod_disable_reason|=2 / full_screen_aod_on=0，
 *   导致“和锁屏样式一致”无法勾选 -> hook 后重新计算该字段（仅去掉 super_wallpaper 排除）。
 *
 * 桌面->锁屏转场补充：
 * - 场景引擎息屏路径（onGoingToSleep else 分支）原发 ForceLock，场景侧 forceAni=true 直接瞬移，
 *   导致“桌面->锁屏”失去转场动画 -> hook 后改发 Lock（场景侧摄像机插值动画），
 *   动画结束停在锁屏视角，作为全屏AOD的静帧背景；锁屏->桌面（Desk 事件）不受影响。
 */
public final class EntryPoint implements IXposedHookLoadPackage {

    private static final String TAG = "SWNoAOD";

    private static final String[] SCENE_PACKAGES = {
            "com.miui.miwallpaper.earth",
            "com.miui.miwallpaper.mars",
            "com.miui.miwallpaper.saturn",
            "com.miui.miwallpaper.moon",
            "com.miui.miwallpaper.snowmountain",
            "com.miui.miwallpaper.geometry",
    };

    // 息屏路径标志：onGoingToSleep 之后由主线程 post 的 runnable 发送 ForceLock 事件，
    // 用该标志识别“正在执行息屏路径”，把 ForceLock（场景侧瞬移）改写为 Lock（摄像机插值动画），
    // 保留桌面->锁屏转场。仅场景包进程、主线程使用，无需同步。
    private static boolean sSleepPath;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        String pkg = lpparam.packageName;
        ClassLoader cl = lpparam.classLoader;
        if ("com.miui.aod".equals(pkg)) {
            hookAodApp(cl);
        } else if ("com.android.systemui".equals(pkg)) {
            hookSystemUi(cl);
        } else if ("com.android.thememanager".equals(pkg)) {
            hookThemeManager(cl);
        } else if ("com.miui.miwallpaper".equals(pkg)) {
            hookMainApp(cl);
        } else {
            for (String scene : SCENE_PACKAGES) {
                if (scene.equals(pkg)) {
                    hookScene(cl, pkg);
                    break;
                }
            }
        }
    }

    // ---------- com.miui.aod (息屏) ----------

    private void hookAodApp(ClassLoader cl) {
        // 设备能力门：aurora 上 SUPPORT_FULL_AOD=false 导致“息屏样式”容器被隐藏
        hookReturnTrue(cl, "com.miui.aod.Utils", "isSupportFsAod", "aod:isSupportFsAod");
        // 支持门：锁屏被超级壁纸占用后 full_screen_aod_support 可能为 0，下拉会被禁用/拒绝
        hookReturnTrue(cl, "com.miui.aod.Utils", "isFullAodSupport", "aod:isFullAodSupport",
                Context.class);
    }

    // ---------- com.android.systemui ----------

    private void hookSystemUi(ClassLoader cl) {
        // 设备级能力：MiuiConfigs.SUPPORT_FULL_AOD 由 support_aod_fullscreen feature 决定，
        // aurora 缺失 -> false。在 SystemUIInitializer.init 时（早于 MiuiFullAodManager 构造）
        // 把该静态字段置 true，与社区 full_aod 模块同思路。
        try {
            XposedHelpers.findAndHookMethod("com.android.systemui.SystemUIInitializer", cl,
                    "init", boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Class<?> configs = XposedHelpers.findClass(
                                        "com.miui.utils.configs.MiuiConfigs", cl);
                                XposedHelpers.setStaticBooleanField(configs, "SUPPORT_FULL_AOD", true);
                                log("sysui:MiuiConfigs.SUPPORT_FULL_AOD -> true");
                            } catch (Throwable t) {
                                logErr("sysui: set SUPPORT_FULL_AOD failed", t);
                            }
                        }
                    });
            log("sysui:SystemUIInitializer.init hooked");
        } catch (Throwable t) {
            logErr("sysui: SystemUIInitializer.init hook failed", t);
        }

        // 壁纸类型支持门：超级壁纸占用锁屏时 onWallpaperChanged() 会把 isWallpaperTypeSupport
        // 置 false，随后 updateSettings() 写 full_screen_aod_support=0 /
        // full_screen_aod_disable_reason|=2 / full_screen_aod_on=0。
        // 重新计算该字段：仅去掉 super_wallpaper 排除项，保留 maml 三方主题/非 MIUI 组件判断。
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.keyguard.fullaod.MiuiFullAodManager", cl,
                    "onWallpaperChanged",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object mgr = XposedHelpers.getObjectField(
                                        param.thisObject, "mMiuiKeyguardWallPaperManager");
                                boolean defTheme = (Boolean) XposedHelpers.callMethod(
                                        mgr, "isDefaultLockScreenTheme");
                                boolean miuiComp = (Boolean) XposedHelpers.callMethod(
                                        mgr, "isMiuiWallpaperComponentUsing");
                                XposedHelpers.setBooleanField(
                                        param.thisObject, "isWallpaperTypeSupport", defTheme && miuiComp);
                                log("sysui:onWallpaperChanged isWallpaperTypeSupport -> "
                                        + (defTheme && miuiComp));
                            } catch (Throwable t) {
                                logErr("sysui: onWallpaperChanged override failed", t);
                            }
                        }
                    });
            log("sysui:MiuiFullAodManager.onWallpaperChanged hooked");
        } catch (Throwable t) {
            logErr("sysui: onWallpaperChanged hook failed", t);
        }
    }

    // ---------- com.android.thememanager ----------

    private void hookThemeManager(ClassLoader cl) {
        hookVoid(cl, "com.android.thememanager.settings.superwallpaper.utils.AodUtils",
                "h", "theme:AodUtils.h",
                Context.class, boolean.class, float.class, float.class, float.class, float.class, String.class);
        hookVoid(cl, "com.android.thememanager.util.WallpaperUtils",
                "y9n", "theme:WallpaperUtils.y9n", String[].class);
    }

    // ---------- com.miui.miwallpaper (main) ----------

    private void hookMainApp(ClassLoader cl) {
        hookVoid(cl, "com.miui.miwallpaper.server.MiuiWallpaperManagerService",
                "J", "main:MiuiWallpaperManagerService.J", String[].class);
    }

    // ---------- 场景包 ----------

    private void hookScene(ClassLoader cl, String pkg) {
        // 门：永不承认正在使用超级壁纸AOD样式（覆盖 Filament 系：earth/moon 与 Unity 系：mars/saturn/geometry）
        hookReturnFalse(cl, "com.miui.miwallpaper.baselib.utils.AodUtils",
                "isAodUsingSuperWallpaperStyle", pkg + ":isAodUsingSuperWallpaperStyle", Context.class);
        // 门：雪山包混淆类 i2.a.b
        hookReturnFalse(cl, "i2.a", "b", pkg + ":i2.a.b", Context.class);

        // 入口：AOD 状态进入方法（存在即阻断，不存在的场景包静默跳过）
        hookVoid(cl, "com.miui.miwallpaper.basesuperwallpaper.SuperWallpaper$SuperWallpaperEngine",
                "goToAodState", pkg + ":goToAodState", int.class);
        hookVoid(cl, "com.miui.miwallpaper.basesuperwallpaper.SuperWallpaper",
                "gotoAod", pkg + ":gotoAod", int.class);
        hookVoid(cl, "com.miui.miwallpaper.basesuperwallpaper.SuperWallpaper",
                "o", pkg + ":SuperWallpaper.o(AOD)", int.class);

        // 命令：吞掉 AOD 相关 onCommand（系统/息屏可能直接下发，绕过 Java 门）
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.basesuperwallpaper.SuperWallpaper$SuperWallpaperEngine", cl,
                    "onCommand", String.class, int.class, int.class, int.class, Bundle.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String action = (String) param.args[0];
                            if ("action_aod".equals(action)
                                    || "action_force_aod".equals(action)
                                    || "action_aod_offset".equals(action)) {
                                log(pkg + ": swallowed onCommand " + action);
                                param.setResult(new Bundle());
                            }
                        }
                    });
        } catch (Throwable t) {
            logErr(pkg + ": onCommand hook failed", t);
        }

        // 桌面->锁屏转场：息屏路径原发 ForceLock（场景侧 forceAni=true 直接瞬移 -> 硬切），
        // 改发 Lock（场景侧摄像机插值动画 -> 保留转场）；动画结束停在锁屏视角，
        // 作为全屏AOD“和锁屏样式一致”的静帧背景。锁屏->桌面（Desk 事件）不受影响。
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.basesuperwallpaper.SuperWallpaper$SuperWallpaperEngine", cl,
                    "onGoingToSleep",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            sSleepPath = true;
                        }
                    });
            log(pkg + ": onGoingToSleep hooked");
        } catch (Throwable t) {
            logErr(pkg + ": onGoingToSleep hook failed", t);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.basesuperwallpaper.SuperWallpaper", cl,
                    "sendFilamentMessage", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (sSleepPath) {
                                sSleepPath = false;
                                if ("ForceLock".equals(param.args[0])) {
                                    param.args[0] = "Lock";
                                    log(pkg + ": sleep ForceLock -> Lock (keep desk->lock transition)");
                                }
                            }
                        }
                    });
            log(pkg + ": sendFilamentMessage hooked");
        } catch (Throwable t) {
            logErr(pkg + ": sendFilamentMessage hook failed", t);
        }
    }

    // ---------- helpers ----------

    private void hookVoid(ClassLoader cl, String className, String methodName, String tag,
                          Class<?>... parameterTypes) {
        try {
            XposedHelpers.findAndHookMethod(className, cl, methodName,
                    callbackArgs(parameterTypes, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            log(tag + ": blocked");
                            param.setResult(null);
                        }
                    }));
            log(tag + ": hooked");
        } catch (Throwable t) {
            logErr(tag + ": hook failed", t);
        }
    }

    private void hookReturnFalse(ClassLoader cl, String className, String methodName, String tag,
                                 Class<?>... parameterTypes) {
        try {
            XposedHelpers.findAndHookMethod(className, cl, methodName,
                    callbackArgs(parameterTypes, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object ctx = (param.args != null && param.args.length > 0) ? param.args[0] : null;
                            if (ctx instanceof Context) {
                                try {
                                    Settings.Secure.putInt(((Context) ctx).getContentResolver(),
                                            "aod_using_super_wallpaper", 0);
                                } catch (Throwable ignored) {
                                }
                            }
                            param.setResult(false);
                        }
                    }));
            log(tag + ": hooked -> forced false");
        } catch (Throwable t) {
            logErr(tag + ": hook failed", t);
        }
    }

    private void hookReturnTrue(ClassLoader cl, String className, String methodName, String tag,
                                Class<?>... parameterTypes) {
        try {
            XposedHelpers.findAndHookMethod(className, cl, methodName,
                    callbackArgs(parameterTypes, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(true);
                        }
                    }));
            log(tag + ": hooked -> forced true");
        } catch (Throwable t) {
            logErr(tag + ": hook failed", t);
        }
    }

    private static Object[] callbackArgs(Class<?>[] parameterTypes, XC_MethodHook callback) {
        Object[] args = new Object[parameterTypes.length + 1];
        System.arraycopy(parameterTypes, 0, args, 0, parameterTypes.length);
        args[parameterTypes.length] = callback;
        return args;
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }

    private static void logErr(String msg, Throwable t) {
        XposedBridge.log(TAG + ": " + msg);
        XposedBridge.log(t);
    }
}
