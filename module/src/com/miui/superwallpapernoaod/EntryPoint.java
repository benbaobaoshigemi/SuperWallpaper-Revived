package com.miui.superwallpapernoaod;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
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
 *   导致“桌面->锁屏”失去转场动画 -> hook 后按引擎状态改写：从桌面息屏改发 Lock（场景侧摄像机
 *   插值动画，动画结束停在锁屏视角，作为全屏AOD“和锁屏样式一致”的静帧背景）；
 *   从锁屏息屏（锁屏->AOD）则丢弃 ForceLock，保持当前静帧不刷新样式（样式变化只允许从桌面出发）。
 * - Filament 系（earth/moon）走 sendFilamentMessage，Unity 系（mars/saturn/geometry）走
 *   sendUnityMessage，统一按同一规则改写。锁屏->桌面（Desk 事件）不受影响。
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
    // 用该标志识别“正在执行息屏路径”。仅场景包进程、主线程使用，无需同步。
    private static boolean sSleepPath;
    // 息屏瞬间引擎所处的壁纸状态（mWallpaperState）：true=从桌面息屏（发 Lock 保留转场）；
    // false=从锁屏息屏即锁屏->AOD（丢弃 ForceLock，保持当前静帧不刷新样式）。
    private static boolean sSleepFromDesk;

    // 设置页模板信息 ContentObserver 窗口标志：编辑页保存样式后写
    // Settings.Secure["constant_template_editor_info"]，设置页
    // SettingsConfigChangeDataSource 的 onChange 会同步读取并把刷新决策交给
    // TemplateApiImpl 的 isLockSetPkgSupportDepth()/isHomeSetPkgSupportDepth()
    // （超级壁纸由 thememanager 设置，两者均为 false -> 预览不刷新）。
    // 仅在 onChange 回调窗口内把这两个判断强制为 true，触发预览刷新。
    // 仅主线程（observer handler 使用 main looper）访问，无需同步。
    private static boolean sInTemplateInfoObserver;

    // 桌面->锁屏 Lock 动画时长：月球 Filament 场景 1500ms，雪山 Lua 摄像机插值约 2s 收敛。
    // 息屏路径原排 300ms 的 MSG_FILAMENT_PAUSE 会在动画中途暂停渲染 -> 顺延到动画结束再暂停，
    // 这样转场能完整播完，且 AOD 静帧恰好停在锁屏视角；唤醒路径 onWakeUp 会 removeMessages(1) 重新排期。
    private static final long LOCK_TRANSITION_PAUSE_MS = 2000L;

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
        // “自定义”（锁屏时钟编辑器入口，长按锁屏 -> 自定义锁屏 -> 编辑页）：
        // 锁屏/桌面壁纸类型为 super_wallpaper/linkage_video 时，
        // MyTemplateViewHolder.shouldShowCustomButton() / LockScreenTransformerLayer 隐藏
        // “自定义”按钮并吞点击。强制 unSupportToEdit=false -> 恢复按钮与点击
        // （副作用：历史模板同样放行编辑，可接受）。
        hookReturnFalse(cl, "com.miui.keyguard.editor.utils.Wallpaper$Companion",
                "unSupportToEdit", "aod:Wallpaper.Companion.unSupportToEdit",
                String.class, String.class);
        // 设置页预览刷新：仅在 observer 窗口内放行深度支持门
        hookTemplateInfoObserverGate(cl, "aod",
                "isLockSetPkgSupportDepth", "isHomeSetPkgSupportDepth");
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
        // “自定义”（锁屏时钟编辑器入口）：锁屏/桌面壁纸类型为 super_wallpaper/linkage_video 时，
        // SettingsMyTemplateViewHolder.bek6() 隐藏“自定义”按钮、SettingsTemplateView.zwy() 吞点击。
        // 强制 ki=false -> 恢复按钮与点击（副作用：历史模板同样放行，可接受）。
        hookReturnFalse(cl, "com.miui.keyguard.editor.utils.Wallpaper$Companion",
                "ki", "theme:Wallpaper.Companion.ki", String.class, String.class);
        // 设置页（系统个性化）预览刷新：同上，仅 observer 窗口内放行
        hookTemplateInfoObserverGate(cl, "theme", "x2", "k");
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

        // 桌面->锁屏转场（Filament 系 earth/moon 走 sendFilamentMessage，Unity 系 mars/saturn/geometry
        // 走 sendUnityMessage，规则一致）：息屏路径原发 ForceLock（场景侧瞬移 -> 硬切）。
        // - 从桌面息屏：改发 Lock（场景侧摄像机插值动画 -> 保留转场），并顺延暂停到动画结束，
        //   动画结束停在锁屏视角，作为全屏AOD“和锁屏样式一致”的静帧背景；
        // - 从锁屏息屏（锁屏->AOD）：丢弃 ForceLock，场景保持当前静帧，不刷新样式。
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.basesuperwallpaper.SuperWallpaper$SuperWallpaperEngine", cl,
                    "onGoingToSleep",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            sSleepPath = true;
                            sSleepFromDesk = true; // 读不到状态时保守按桌面处理，不破坏既有行为
                            try {
                                String state = (String) XposedHelpers.getObjectField(
                                        param.thisObject, "mWallpaperState");
                                sSleepFromDesk = "show_desk".equals(state);
                            } catch (Throwable ignored) {
                            }
                            log(pkg + ": onGoingToSleep fromDesk=" + sSleepFromDesk);
                        }
                    });
            log(pkg + ": onGoingToSleep hooked");
        } catch (Throwable t) {
            logErr(pkg + ": onGoingToSleep hook failed", t);
        }
        hookSleepLockRewrite(cl, pkg, "sendFilamentMessage");
        hookSleepLockRewrite(cl, pkg, "sendUnityMessage");

        // 月球场景（com.miui.mrengine.MoonMrePlayer）：
        // - 唤醒路径会发 ForceLock：LOCK 状态时重随机位相+瞬移，FLOCK 状态时 resume+autoRot 重启
        //   -> 唤醒/锁屏切换会刷新样式；锁屏->AOD 也会因 FLOCK 重放转场而刷新。
        // - 只允许“从桌面出发的 Lock 动画”：ForceLock 在 LOCK/FLOCK 时跳过（保持静帧不重随机），
        //   Lock 在 FLOCK 时跳过（防止重放转场）。桌面(Lock from HOME)转场保持原样。
        if ("com.miui.miwallpaper.moon".equals(pkg)) {
            try {
                XposedHelpers.findAndHookMethod("com.miui.mrengine.MoonMrePlayer", cl,
                        "sendMessage", String.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                String ev = (String) param.args[0];
                                if (!"Lock".equals(ev) && !"ForceLock".equals(ev)) {
                                    return;
                                }
                                try {
                                    Object state = XposedHelpers.getObjectField(
                                            param.thisObject, "curMoonState");
                                    String st = state == null ? "" : state.toString();
                                    if ("ForceLock".equals(ev)
                                            && ("LOCK".equals(st) || "FLOCK".equals(st))) {
                                        log(pkg + ": moon " + ev + " in " + st
                                                + " -> skipped (keep static, no re-randomize)");
                                        param.setResult(null);
                                    } else if ("Lock".equals(ev) && "FLOCK".equals(st)) {
                                        log(pkg + ": moon Lock in FLOCK -> skipped (no replay)");
                                        param.setResult(null);
                                    }
                                } catch (Throwable t) {
                                    logErr(pkg + ": moon sendMessage hook failed", t);
                                }
                            }
                        });
                log(pkg + ": MoonMrePlayer.sendMessage hooked");
            } catch (Throwable t) {
                logErr(pkg + ": MoonMrePlayer.sendMessage hook failed", t);
            }
        }

        // 地球场景（com.miui.mrengine.EarthMrePlayer）：
        // lockTransForm() 每次都用 getTimeScale()（当前时刻）重算锁屏太阳/光照方向（getLockSun），
        // 唤醒路径的 ForceLock 会在唤醒时刻重算目标 -> 与 AOD 定格帧（睡眠时刻构图）不一致，造成
        // AOD->锁屏构图跳变。LOCK 状态收到 ForceLock 时跳过（保持当前静帧），HOME 状态放行（初始锁屏）。
        if ("com.miui.miwallpaper.earth".equals(pkg)) {
            try {
                XposedHelpers.findAndHookMethod("com.miui.mrengine.EarthMrePlayer", cl,
                        "sendMessage", String.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (!"ForceLock".equals(param.args[0])) {
                                    return;
                                }
                                try {
                                    Object state = XposedHelpers.getObjectField(
                                            param.thisObject, "curState");
                                    String st = state == null ? "" : state.toString();
                                    if ("LOCK".equals(st)) {
                                        log(pkg + ": earth ForceLock in LOCK -> skipped (keep static frame)");
                                        param.setResult(null);
                                    }
                                } catch (Throwable t) {
                                    logErr(pkg + ": earth sendMessage hook failed", t);
                                }
                            }
                        });
                log(pkg + ": EarthMrePlayer.sendMessage hooked");
            } catch (Throwable t) {
                logErr(pkg + ": EarthMrePlayer.sendMessage hook failed", t);
            }
        }

        // 雪山（整包混淆，无 onGoingToSleep/sendFilamentMessage）适配：
        // - Android U 息屏走 onCommand goingtosleep 内联分支 -> post j2.f(b!=0) -> r("ForceLock")；
        // - 旧版广播路径 q() -> post SuperWallpaper$e -> r("ForceLock")。
        // 两处 run() 前置睡眠标志，r(String) 统一把 ForceLock 改写为 Lock 并顺延暂停。
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.basesuperwallpaper.SuperWallpaper$e", cl,
                    "run",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            sSleepPath = true;
                        }
                    });
            log(pkg + ": snowmountain $e.run hooked");
        } catch (Throwable t) {
            logErr(pkg + ": snowmountain $e.run hook failed", t);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "j2.f", cl,
                    "run",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                // b==0 是 keyguardgoingaway 后的 u=false 兜底（不发送），
                                // b!=0（goingtosleep 传来）才发 ForceLock，避免标志泄漏。
                                if (XposedHelpers.getIntField(param.thisObject, "b") != 0) {
                                    sSleepPath = true;
                                }
                            } catch (Throwable t) {
                                logErr(pkg + ": snowmountain j2.f read b failed", t);
                            }
                        }
                    });
            log(pkg + ": snowmountain j2.f.run hooked");
        } catch (Throwable t) {
            logErr(pkg + ": snowmountain j2.f.run hook failed", t);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.basesuperwallpaper.SuperWallpaper", cl,
                    "r", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (sSleepPath) {
                                sSleepPath = false;
                                if ("ForceLock".equals(param.args[0])) {
                                    param.args[0] = "Lock";
                                    log(pkg + ": snowmountain sleep ForceLock -> Lock (keep desk->lock transition)");
                                    rescheduleFilamentPause(param.thisObject);
                                }
                            }
                        }
                    });
            log(pkg + ": snowmountain r(String) hooked");
        } catch (Throwable t) {
            logErr(pkg + ": snowmountain r(String) hook failed", t);
        }
    }

    // 息屏路径消息改写：onGoingToSleep 置位后，把 ForceLock 按来源改写——
    // 从桌面息屏 -> Lock（保留转场）；从锁屏息屏 -> 丢弃（保持静帧）。Filament/Unity 通用。
    private void hookSleepLockRewrite(ClassLoader cl, String pkg, String methodName) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.basesuperwallpaper.SuperWallpaper", cl,
                    methodName, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!sSleepPath || !"ForceLock".equals(param.args[0])) {
                                return;
                            }
                            sSleepPath = false;
                            if (sSleepFromDesk) {
                                param.args[0] = "Lock";
                                log(pkg + ": sleep(desk) " + methodName + " ForceLock -> Lock");
                                rescheduleFilamentPause(param.thisObject);
                            } else {
                                log(pkg + ": sleep(lock->aod) " + methodName
                                        + " drop ForceLock (static frame)");
                                param.setResult(null);
                            }
                        }
                    });
            log(pkg + ": " + methodName + " hooked");
        } catch (Throwable t) {
            logErr(pkg + ": " + methodName + " hook failed", t);
        }
    }

    // 设置页模板信息刷新门控：仅当 SettingsConfigChangeDataSource 的 ContentObserver.onChange
    // 处理窗口内，把 TemplateApiImpl 的“壁纸由 com.miui.aod 设置（支持景深）”判断强制为 true，
    // 让编辑样式后的设置页预览刷新。aod 包方法名为 isLockSetPkgSupportDepth/isHomeSetPkgSupportDepth，
    // thememanager 混淆为 x2/k。窗口外不干预（apply 流程的 needUpdateConfig 等依赖原值）。
    private void hookTemplateInfoObserverGate(ClassLoader cl, String tag,
                                              String lockMethod, String homeMethod) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.keyguard.editor.settings.data.SettingsConfigChangeDataSource$getSettingsConfigChangedEventFlow$1$settingsConfigChangedListener$1", cl,
                    "onChange", boolean.class, Uri.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            sInTemplateInfoObserver = true;
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sInTemplateInfoObserver = false;
                        }
                    });
            log(tag + ": settings observer onChange hooked");
        } catch (Throwable t) {
            logErr(tag + ": settings observer onChange hook failed", t);
        }
        hookReturnTrueInObserver(cl, "com.miui.keyguard.editor.data.template.TemplateApiImpl",
                lockMethod, tag + ":TemplateApiImpl." + lockMethod);
        hookReturnTrueInObserver(cl, "com.miui.keyguard.editor.data.template.TemplateApiImpl",
                homeMethod, tag + ":TemplateApiImpl." + homeMethod);
    }

    private void hookReturnTrueInObserver(ClassLoader cl, String className, String methodName, String tag) {
        try {
            XposedHelpers.findAndHookMethod(className, cl, methodName,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (sInTemplateInfoObserver) {
                                param.setResult(true);
                            }
                        }
                    });
            log(tag + ": hooked -> forced true in observer window");
        } catch (Throwable t) {
            logErr(tag + ": hook failed", t);
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

    // 把息屏路径排的 MSG_FILAMENT_PAUSE(what=1, 300ms) 顺延到 Lock 动画结束之后，
    // 避免转场中途被暂停掐断。可读包字段名 mHandler，雪山混淆包为 J。
    private static void rescheduleFilamentPause(Object superWallpaper) {
        Object handler = null;
        try {
            handler = XposedHelpers.getObjectField(superWallpaper, "mHandler");
        } catch (Throwable ignored) {
        }
        if (handler == null) {
            try {
                handler = XposedHelpers.getObjectField(superWallpaper, "J");
            } catch (Throwable ignored) {
            }
        }
        if (handler instanceof Handler) {
            ((Handler) handler).removeMessages(1);
            ((Handler) handler).sendEmptyMessageDelayed(1, LOCK_TRANSITION_PAUSE_MS);
        }
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }

    private static void logErr(String msg, Throwable t) {
        XposedBridge.log(TAG + ": " + msg);
        XposedBridge.log(t);
    }
}
