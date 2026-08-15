package com.miui.superwallpapernoaod;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.WallpaperManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
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
 * - “经典超级壁纸AOD”开启时，从桌面进入全屏 AOD 会在一次 onGoingToSleep 调用内放行原厂
 *   AOD 样式判断和入口，恢复 AOD 样式轮换与原生动画；唤醒后发送 Lock 返回锁屏构图。
 *   锁屏->AOD 不进入该分支。
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
    private static boolean sForceClassicAodStyle;
    private static boolean sClassicAodPending;
    private static boolean sClassicAodActive;
    private static boolean sRestoreLockOnWake;
    // 实验性：尽量放行所有可 Hook 到的场景渲染暂停，包括 AOD/Doze。
    private static boolean sContinueAodRotation;
    // AOD 唤醒回锁屏期间吞掉所有 Lock/ForceLock，直到进入桌面，保留最终静止构图。
    private static boolean sSuppressWakeLockEvent;
    private static long sEarlySleepLockUptime;
    private static boolean sReplayingDelayedScreenFade;
    private static int sDesktopFollowScalePercent = 100;
    private static int sDesktopFollowDampingPercent = 100;
    private static int sDesktopFollowResponsePercent = 100;
    private static long sDesktopFollowSettingsLoadedAt;
    private static final DesktopFollowSpring sDesktopFollowSpring = new DesktopFollowSpring();
    private static final ThreadLocal<Boolean> sDispatchingDesktopFollow = new ThreadLocal<>();
    private static Handler sDesktopFollowHandler;
    private static Object sDesktopFollowTarget;
    private static String sDesktopFollowMethodName;
    private static final Runnable sDesktopFollowContinuation =
            EntryPoint::continueDesktopFollow;

    private static final long EARLY_LOCK_DEDUP_WINDOW_MS = 3000L;
    private static final long NON_FULLSCREEN_AOD_HEAD_START_MS = 250L;
    private static final long CLASSIC_AOD_WAKE_FALLBACK_MS = 100L;

    // 设置页模板信息 ContentObserver 窗口标志：编辑页保存样式后写
    // Settings.Secure["constant_template_editor_info"]，设置页
    // SettingsConfigChangeDataSource 的 onChange 会同步读取并把刷新决策交给
    // TemplateApiImpl 的 isLockSetPkgSupportDepth()/isHomeSetPkgSupportDepth()
    // （超级壁纸由 thememanager 设置，两者均为 false -> 预览不刷新）。
    // 仅在 onChange 回调窗口内把这两个判断强制为 true，触发预览刷新。
    // 仅主线程（observer handler 使用 main looper）访问，无需同步。
    private static boolean sInTemplateInfoObserver;

    // SystemUI 在一次 AOD/锁屏切换中约每 8ms 调用矩阵计算；只记录每次连续缩放序列的首帧，
    // 避免污染系统日志。数值回到 1.0 时复位，下一次切换会重新记录。
    private static boolean sZoomSequenceLogged;
    private static Activity sLauncherActivity;
    private static IBinder sLauncherWindowToken;
    private static int sLauncherWallpaperZoomState;
    private static long sKeyguardGoingAwayUptime;

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
        } else if ("com.miui.home".equals(pkg)) {
            hookLauncherAppTransitionZoom(cl);
        } else {
            for (String scene : SCENE_PACKAGES) {
                if (scene.equals(pkg)) {
                    if ("com.miui.miwallpaper.saturn".equals(pkg)) {
                        try {
                            System.loadLibrary("saturnstyle");
                            log(pkg + ": native lock style hook loaded");
                        } catch (Throwable t) {
                            logErr(pkg + ": native lock style hook load failed", t);
                        }
                    }
                    hookScene(cl, pkg);
                    break;
                }
            }
        }
    }

    private void hookLauncherAppTransitionZoom(ClassLoader cl) {
        try {
            Class<?> wallpaperElement = XposedHelpers.findClass(
                    "com.miui.home.recents.anim.SystemWallpaperElement", cl);
            Class<?> wallpaperParam = XposedHelpers.findClass(
                    "com.miui.home.recents.anim.WallpaperParam", cl);
            XC_MethodHook wallpaperZoomHook = new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!ModuleSettings.appTransitionZoom()) {
                                sLauncherWallpaperZoomState = 0;
                                return;
                            }
                            float zoom = XposedHelpers.getFloatField(param.args[0], "zoomOut");
                            int state = zoom > 1.10f ? 1 : zoom <= 1.06f ? 2 : 0;
                            if (state == 0 || state == sLauncherWallpaperZoomState) return;
                            Activity activity = sLauncherActivity;
                            IBinder token = sLauncherWindowToken;
                            if (activity == null || token == null) return;
                            PowerManager powerManager = activity.getSystemService(PowerManager.class);
                            KeyguardManager keyguardManager = activity.getSystemService(KeyguardManager.class);
                            if (powerManager == null || !powerManager.isInteractive()
                                    || (keyguardManager != null && keyguardManager.isKeyguardLocked())) {
                                sLauncherWallpaperZoomState = 0;
                                return;
                            }
                            String action = state == 1
                                    ? "action_open_folder" : "action_close_folder";
                            try {
                                WallpaperManager.getInstance(activity).sendWallpaperCommand(
                                        token, action, 0, 0, 0, null);
                                sLauncherWallpaperZoomState = state;
                                log("launcher: OEM wallpaper zoom=" + zoom + " -> " + action);
                            } catch (Throwable t) {
                                logErr("launcher: OEM wallpaper zoom command failed", t);
                            }
                        }
                    };
            XposedHelpers.findAndHookMethod(wallpaperElement, "animTo", wallpaperParam,
                    wallpaperZoomHook);
            XposedHelpers.findAndHookMethod(wallpaperElement, "setTo", wallpaperParam,
                    wallpaperZoomHook);
            Class<?> launcherClass = XposedHelpers.findClass("com.miui.home.launcher.Launcher", cl);
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!launcherClass.isInstance(param.thisObject)) return;
                    Activity activity = (Activity) param.thisObject;
                    sLauncherActivity = activity;
                    sLauncherWindowToken = activity.getWindow().getDecorView().getWindowToken();
                }
            });
            log("launcher: OEM wallpaper state zoom hook hooked");
        } catch (Throwable t) {
            logErr("launcher: remote app transition wallpaper zoom hook failed", t);
        }
    }

    // ---------- com.miui.aod (息屏) ----------

    private void hookAodApp(ClassLoader cl) {
        // 设备能力门：aurora 上 SUPPORT_FULL_AOD=false 导致“息屏样式”容器被隐藏
        hookReturnTrueWhenForceFullAod(cl, "com.miui.aod.Utils", "isSupportFsAod",
                "aod:isSupportFsAod");
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
                            if (!ModuleSettings.forceFullAod()) {
                                log("sysui: force full AOD disabled; keep OEM capability");
                                return;
                            }
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

        // 原厂全屏 AOD 的压暗链路：MiuiFullAodManager 根据息屏亮度计算
        // wallpaperBlack(约 0.3～0.8)，再由 KeyguardPanelViewController
        // 通过 ViewRootImpl/SurfaceControl 应用到壁纸。默认复用该逻辑；
        // 关闭开关时仅跳过全屏 AOD 的这一步，不影响普通锁屏转场。
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.keyguard.panel.KeyguardPanelViewController", cl,
                    "doWallpaperBlackAnim", int.class, float.class, boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (ModuleSettings.reuseOemFullAodDimming()) {
                                return;
                            }
                            try {
                                Object manager = XposedHelpers.getObjectField(
                                        param.thisObject, "miuiFullAodManager");
                                boolean fullAod = (Boolean) XposedHelpers.callMethod(manager, "fullAodEnable");
                                if (fullAod) {
                                    log("sysui: OEM full AOD wallpaper dimming disabled");
                                    param.setResult(null);
                                }
                            } catch (Throwable t) {
                                logErr("sysui: full AOD dimming switch failed", t);
                            }
                        }
                    });
            log("sysui:KeyguardPanelViewController.doWallpaperBlackAnim hooked");
        } catch (Throwable t) {
            logErr("sysui: doWallpaperBlackAnim hook failed", t);
        }

        // 全屏 AOD -> 锁屏的缩放由 SystemUI 统一处理，不走各场景 WallpaperService
        // 的 onZoomChanged()。初始化和唤醒动画通过 setWallpaperZoom()/
        // calculateWallpaperMatrixArray() 修改同一组壁纸 Surface。SystemUI 不按壁纸包
        // 分流，因此该拦截覆盖所有超级壁纸场景。
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.keyguard.panel.KeyguardPanelViewController", cl,
                    "setWallpaperZoom", float.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (ModuleSettings.disableAodToLockZoom()) {
                                float zoom = (Float) param.args[0];
                                if (Math.abs(zoom - 0.75f) < 0.001f) {
                                    log("sysui: AOD->lock wallpaper zoom replaced: " + zoom + " -> 1.0");
                                    param.args[0] = 1.0f;
                                }
                            }
                        }
                    });
            log("sysui:KeyguardPanelViewController.setWallpaperZoom hooked");
        } catch (Throwable t) {
            logErr("sysui: setWallpaperZoom hook failed", t);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.keyguard.panel.KeyguardPanelViewController", cl,
                    "calculateWallpaperMatrixArray", float.class, float.class, float.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (ModuleSettings.disableAodToLockZoom()) {
                                float scale = (Float) param.args[0];
                                if (Math.abs(scale - 1.0f) > 0.001f) {
                                    if (!sZoomSequenceLogged) {
                                        sZoomSequenceLogged = true;
                                        log("sysui: AOD->lock wallpaper matrix scale replaced: "
                                                + scale + " -> 1.0");
                                    }
                                    param.args[0] = 1.0f;
                                } else {
                                    sZoomSequenceLogged = false;
                                }
                            }
                        }
                    });
            log("sysui:KeyguardPanelViewController.calculateWallpaperMatrixArray hooked");
        } catch (Throwable t) {
            logErr("sysui: calculateWallpaperMatrixArray hook failed", t);
        }
        hookNonFullscreenAodTransition(cl);
    }


    private void hookNonFullscreenAodTransition(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.keyguard.screenfade.DisplayStateShaderController"
                            + "$updateScreenFadeState$1",
                    cl, "run", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (XposedHelpers.getBooleanField(param.thisObject, "$wakeup")) {
                                    return;
                                }
                                if (sReplayingDelayedScreenFade) {
                                    return;
                                }
                                if (!ModuleSettings.nonFullscreenAodTransition()) {
                                    return;
                                }
                                Object fadeController = XposedHelpers.getObjectField(
                                        param.thisObject, "this$0");
                                Object injector = XposedHelpers.getObjectField(
                                        fadeController, "mKeyguardPanelViewInjector");
                                Object panel = XposedHelpers.callMethod(
                                        injector, "getKeyguardPanelViewController");
                                Context context = (Context) XposedHelpers.getObjectField(
                                        fadeController, "mContext");
                                if (Settings.Secure.getInt(context.getContentResolver(),
                                        "full_screen_aod_on", 0) != 0) {
                                    return;
                                }
                                Class<?> commonUtil = XposedHelpers.findClass(
                                        "com.miui.systemui.util.CommonUtil", cl);
                                if (!"super_wallpaper".equals(XposedHelpers.getStaticObjectField(
                                        commonUtil, "sKeyguardWallpaperType"))) {
                                    return;
                                }
                                if (!(Boolean) XposedHelpers.callStaticMethod(
                                        commonUtil, "isTopActivityLauncher", context)) {
                                    return;
                                }
                                Object rootView = XposedHelpers.getObjectField(
                                        panel, "keyguardRootView");
                                IBinder token = (IBinder) XposedHelpers.callMethod(
                                        rootView, "getWindowToken");
                                if (token == null) {
                                    return;
                                }
                                WallpaperManager.getInstance(context).sendWallpaperCommand(
                                        token, "action_lock", 0, 0, 0, null);
                                Handler handler = (Handler) XposedHelpers.getObjectField(
                                        fadeController, "mScreenFadeHandler");
                                Object delayedRun = param.thisObject;
                                handler.postDelayed(() -> {
                                    sReplayingDelayedScreenFade = true;
                                    try {
                                        XposedHelpers.callMethod(delayedRun, "run");
                                    } catch (Throwable t) {
                                        logErr("sysui: delayed ScreenFade run failed", t);
                                    } finally {
                                        sReplayingDelayedScreenFade = false;
                                    }
                                }, NON_FULLSCREEN_AOD_HEAD_START_MS);
                                log("sysui: non-fullscreen AOD Lock sent; ScreenFade delayed "
                                        + NON_FULLSCREEN_AOD_HEAD_START_MS + "ms");
                                param.setResult(null);
                            } catch (Throwable t) {
                                logErr("sysui: non-fullscreen AOD transition failed", t);
                            }
                        }
                    });
            log("sysui: non-fullscreen AOD transition hooked");
        } catch (Throwable t) {
            logErr("sysui: non-fullscreen AOD transition hook failed", t);
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
        hookSceneAodStyleGate(cl, pkg);
        // 门：雪山包混淆类 i2.a.b
        hookReturnFalse(cl, "i2.a", "b", pkg + ":i2.a.b", Context.class);

        // 入口：AOD 状态进入方法（存在即阻断，不存在的场景包静默跳过）
        hookSceneAodEntryGate(cl, pkg,
                "com.miui.miwallpaper.basesuperwallpaper.SuperWallpaper$SuperWallpaperEngine",
                "goToAodState");
        hookSceneAodEntryGate(cl, pkg,
                "com.miui.miwallpaper.basesuperwallpaper.SuperWallpaper", "gotoAod");
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
                            if ("android.wallpaper.wakingup".equals(action)
                                    && ModuleSettings.continueAodRotation()) {
                                sContinueAodRotation = true;
                                sSuppressWakeLockEvent = true;
                                log(pkg + ": command wakingup -> hold all lock reset events until going away");
                            } else if ("android.wallpaper.keyguardgoingaway".equals(action)) {
                                sContinueAodRotation = false;
                                sSuppressWakeLockEvent = false;
                            }
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
         // - 从桌面息屏：改发 Lock，保留场景侧摄像机插值转场；暂停时机不改写。
         // - 从锁屏息屏（锁屏->AOD）：实验开关开启时改发 Lock，让场景进入锁屏动画链路；
         //   暂停仍由原厂 Handler/系统生命周期处理。
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.basesuperwallpaper.SuperWallpaper$SuperWallpaperEngine", cl,
                    "onGoingToSleep",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            sSleepPath = true;
                            sContinueAodRotation = ModuleSettings.continueAodRotation();
                            sSuppressWakeLockEvent = false;
                            sSleepFromDesk = true; // 读不到状态时保守按桌面处理，不破坏既有行为
                            try {
                                String state = (String) XposedHelpers.getObjectField(
                                        param.thisObject, "mWallpaperState");
                                sSleepFromDesk = "show_desk".equals(state);
                            } catch (Throwable ignored) {
                            }
                            log(pkg + ": onGoingToSleep fromDesk=" + sSleepFromDesk
                                    + " aodFinitePlayback=" + sContinueAodRotation);
                            sForceClassicAodStyle = false;
                            if (sSleepFromDesk
                                    && ModuleSettings.classicSuperWallpaperAod()) {
                                sClassicAodPending = false;
                                sClassicAodActive = false;
                                try {
                                    Object service = XposedHelpers.getObjectField(
                                            param.thisObject, "this$0");
                                    Context context = (Context) service;
                                    if (Settings.Secure.getInt(context.getContentResolver(),
                                            "full_screen_aod_on", 0) != 0) {
                                        sForceClassicAodStyle = true;
                                        log(pkg + ": desktop full-AOD -> enable OEM AOD style path");
                                    }
                                } catch (Throwable t) {
                                    logErr(pkg + ": desktop full-AOD style gate failed", t);
                                }
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (sForceClassicAodStyle) {
                                sForceClassicAodStyle = false;
                                if (sClassicAodActive) {
                                    sSleepPath = false;
                                }
                            }
                        }
                    });
            log(pkg + ": onGoingToSleep hooked");
        } catch (Throwable t) {
            logErr(pkg + ": onGoingToSleep hook failed", t);
        }
        hookSleepLockRewrite(cl, pkg, "sendFilamentMessage");
        hookSleepLockRewrite(cl, pkg, "sendUnityMessage");
        hookDesktopFollowControls(cl, pkg, "sendFilamentMessage");
        hookDesktopFollowControls(cl, pkg, "sendUnityMessage");
        hookSceneRotationLifecycle(cl, pkg);
        hookVisibilityPauseGate(cl, pkg);
        hookRendererPause(cl, pkg, "unityPause");
        hookRendererPause(cl, pkg, "filamentPause");
        if ("com.miui.miwallpaper.snowmountain".equals(pkg)) {
            hookRendererPause(cl, pkg, "h");
        }
        hookWakeRendererResume(cl, pkg, "unityResume");
        hookWakeRendererResume(cl, pkg, "filamentResume");
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
        // 两处 run() 前置睡眠标志，r(String) 统一把 ForceLock 改写为 Lock。
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
                                    sContinueAodRotation = ModuleSettings.continueAodRotation();
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
                            if (sRestoreLockOnWake
                                    && ("ForceLock".equals(param.args[0])
                                    || "Lock".equals(param.args[0]))) {
                                sRestoreLockOnWake = false;
                                param.args[0] = "Lock";
                                log(pkg + ": classic AOD wake event -> Lock");
                                return;
                            }
                            if (sSuppressWakeLockEvent
                                    && ("ForceLock".equals(param.args[0])
                                    || "Lock".equals(param.args[0]))
                                    && ModuleSettings.continueAodRotation()) {
                                log(pkg + ": snowmountain wake " + param.args[0]
                                        + " suppressed (hold static frame until going away)");
                                param.setResult(null);
                                return;
                            }
                            if ("Lock".equals(param.args[0]) && !sSleepPath
                                    && ModuleSettings.nonFullscreenAodTransition()) {
                                sEarlySleepLockUptime = SystemClock.elapsedRealtime();
                                log(pkg + ": early non-fullscreen AOD Lock received");
                            }
                            if (sSleepPath) {
                                sSleepPath = false;
                                sContinueAodRotation = ModuleSettings.continueAodRotation();
                                sSuppressWakeLockEvent = false;
                                if ("ForceLock".equals(param.args[0])) {
                                    if (sEarlySleepLockUptime != 0L
                                            && SystemClock.elapsedRealtime()
                                            - sEarlySleepLockUptime < EARLY_LOCK_DEDUP_WINDOW_MS) {
                                        sEarlySleepLockUptime = 0L;
                                        log(pkg + ": snowmountain duplicate sleep ForceLock skipped");
                                        param.setResult(null);
                                        return;
                                    }
                                    param.args[0] = "Lock";
                                    log(pkg + ": snowmountain sleep ForceLock -> Lock (OEM pause lifecycle)");
                                    if (sContinueAodRotation) {
                                        rescheduleRendererPause(param.thisObject);
                                    }
                                }
                            }
                        }
                    });
            log(pkg + ": snowmountain r(String) hooked");
        } catch (Throwable t) {
            logErr(pkg + ": snowmountain r(String) hook failed", t);
        }
        hookDesktopFollowControls(cl, pkg, "r");
    }

    private void hookSceneAodStyleGate(ClassLoader cl, String pkg) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.baselib.utils.AodUtils", cl,
                    "isAodUsingSuperWallpaperStyle", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            boolean allow = sForceClassicAodStyle;
                            if (allow) {
                                log(pkg + ": OEM AOD style check allowed for desktop full-AOD");
                            }
                            param.setResult(allow);
                        }
                    });
            log(pkg + ": scene AOD style gate hooked");
        } catch (Throwable t) {
            logErr(pkg + ": scene AOD style gate hook failed", t);
        }
    }

    private void hookSceneAodEntryGate(
            ClassLoader cl, String pkg, String className, String methodName) {
        try {
            XposedHelpers.findAndHookMethod(className, cl, methodName, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (sForceClassicAodStyle) {
                                sClassicAodPending = true;
                                log(pkg + ": " + methodName
                                        + " allowed for desktop full-AOD");
                                return;
                            }
                            param.setResult(null);
                        }
                    });
            log(pkg + ": " + methodName + " AOD entry gate hooked");
        } catch (Throwable ignored) {
            // Scene engines expose only the AOD entry method used by that engine version.
        }
    }

    // 息屏路径消息改写：桌面->锁屏保留 Lock 转场；实验开启时，锁屏->AOD
    // 也触发一次 Lock，让场景进入原厂锁屏动画链路。
    private void hookSleepLockRewrite(ClassLoader cl, String pkg, String methodName) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.basesuperwallpaper.SuperWallpaper", cl,
                    methodName, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (sClassicAodPending
                                    && param.args[0] instanceof String
                                    && ((String) param.args[0]).startsWith("AOD_")) {
                                sClassicAodPending = false;
                                sClassicAodActive = true;
                                sSleepPath = false;
                                log(pkg + ": classic AOD scene activated by " + param.args[0]);
                            }
                            if (sRestoreLockOnWake
                                    && ("ForceLock".equals(param.args[0])
                                    || "Lock".equals(param.args[0]))) {
                                sRestoreLockOnWake = false;
                                param.args[0] = "Lock";
                                log(pkg + ": classic AOD wake event -> Lock");
                                return;
                            }
                            if (sSuppressWakeLockEvent
                                    && ("ForceLock".equals(param.args[0])
                                    || "Lock".equals(param.args[0]))
                                    && ModuleSettings.continueAodRotation()) {
                                log(pkg + ": wake " + param.args[0]
                                        + " suppressed (hold static frame until going away)");
                                param.setResult(null);
                                return;
                            }
                            if ("Lock".equals(param.args[0]) && !sSleepPath
                                    && ModuleSettings.nonFullscreenAodTransition()) {
                                sEarlySleepLockUptime = SystemClock.elapsedRealtime();
                                log(pkg + ": early non-fullscreen AOD Lock received");
                                return;
                            }
                            if (!sSleepPath || !"ForceLock".equals(param.args[0])) {
                                return;
                            }
                            sSleepPath = false;
                            sClassicAodPending = false;
                            if (sEarlySleepLockUptime != 0L
                                    && SystemClock.elapsedRealtime() - sEarlySleepLockUptime
                                    < EARLY_LOCK_DEDUP_WINDOW_MS) {
                                sEarlySleepLockUptime = 0L;
                                log(pkg + ": duplicate sleep " + methodName
                                        + " ForceLock skipped");
                                param.setResult(null);
                                return;
                            }
                            if (sSleepFromDesk) {
                                param.args[0] = "Lock";
                                log(pkg + ": sleep(desk) " + methodName
                                        + " ForceLock -> Lock (OEM pause lifecycle)");
                                rescheduleRendererPause(param.thisObject);
                            } else if (sContinueAodRotation) {
                                param.args[0] = "Lock";
                                log(pkg + ": sleep(lock->aod) " + methodName
                                        + " ForceLock -> Lock (finite playback, OEM pause)");
                            } else {
                                log(pkg + ": sleep(lock->aod) " + methodName
                                        + " drop ForceLock (experiment disabled)");
                                param.setResult(null);
                            }
                        }

                    });
            log(pkg + ": " + methodName + " hooked");
        } catch (Throwable t) {
            logErr(pkg + ": " + methodName + " hook failed", t);
        }
    }

    private void hookDesktopFollowControls(ClassLoader cl, String pkg, String methodName) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.basesuperwallpaper.SuperWallpaper", cl,
                    methodName, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (Boolean.TRUE.equals(sDispatchingDesktopFollow.get())
                                    || !(param.args[0] instanceof String)) {
                                return;
                            }
                            String event = (String) param.args[0];
                            if (!event.startsWith("Offset_")) {
                                if ("Desk".equals(event) || "Lock".equals(event)
                                        || "ForceLock".equals(event)
                                        || event.startsWith("AOD_")) {
                                    resetDesktopFollow();
                                }
                                return;
                            }
                            long now = SystemClock.elapsedRealtime();
                            if (now - sDesktopFollowSettingsLoadedAt >= 1000L) {
                                int oldScale = sDesktopFollowScalePercent;
                                int oldDamping = sDesktopFollowDampingPercent;
                                int oldResponse = sDesktopFollowResponsePercent;
                                sDesktopFollowScalePercent = ModuleSettings.desktopFollowScale();
                                sDesktopFollowDampingPercent =
                                        ModuleSettings.desktopFollowDamping();
                                sDesktopFollowResponsePercent =
                                        ModuleSettings.desktopFollowResponse();
                                sDesktopFollowSettingsLoadedAt = now;
                                if (oldScale != sDesktopFollowScalePercent
                                        || oldDamping != sDesktopFollowDampingPercent
                                        || oldResponse != sDesktopFollowResponsePercent) {
                                    resetDesktopFollow();
                                    log(pkg + ": desktop follow scale="
                                            + sDesktopFollowScalePercent + "% damping="
                                            + sDesktopFollowDampingPercent + "% response="
                                            + sDesktopFollowResponsePercent + "%");
                                }
                            }
                            try {
                                float offset = Float.parseFloat(event.substring(7));
                                float target = 50.0f + (offset - 50.0f)
                                        * sDesktopFollowScalePercent / 100.0f;
                                if (sDesktopFollowDampingPercent == 100
                                        && sDesktopFollowResponsePercent == 100) {
                                    resetDesktopFollow();
                                    param.args[0] = formatDesktopOffset(target);
                                    return;
                                }
                                float filtered = sDesktopFollowSpring.onInput(
                                        target, sDesktopFollowDampingPercent,
                                        sDesktopFollowResponsePercent, now);
                                sDesktopFollowTarget = param.thisObject;
                                sDesktopFollowMethodName = methodName;
                                param.args[0] = formatDesktopOffset(filtered);
                                scheduleDesktopFollowContinuation();
                            } catch (NumberFormatException e) {
                                logErr(pkg + ": invalid desktop offset " + event, e);
                            }
                        }
                    });
            log(pkg + ": desktop follow controls hooked via " + methodName);
        } catch (Throwable ignored) {
            // Scene engines expose only one of the known message methods.
        }
    }

    private static String formatDesktopOffset(float offset) {
        float rounded = Math.round(offset * 10.0f) / 10.0f;
        return "Offset_" + Float.toString(rounded);
    }

    private static void scheduleDesktopFollowContinuation() {
        if (!sDesktopFollowSpring.hasPending()) return;
        if (sDesktopFollowHandler == null) {
            sDesktopFollowHandler = new Handler(Looper.getMainLooper());
        }
        sDesktopFollowHandler.removeCallbacks(sDesktopFollowContinuation);
        sDesktopFollowHandler.postDelayed(sDesktopFollowContinuation, 8L);
    }

    private static void continueDesktopFollow() {
        if (sDesktopFollowTarget == null || sDesktopFollowMethodName == null) {
            resetDesktopFollow();
            return;
        }
        float value = sDesktopFollowSpring.poll(
                sDesktopFollowDampingPercent, sDesktopFollowResponsePercent,
                SystemClock.elapsedRealtime());
        if (!Float.isNaN(value)) {
            try {
                sDispatchingDesktopFollow.set(Boolean.TRUE);
                XposedHelpers.callMethod(sDesktopFollowTarget,
                        sDesktopFollowMethodName, formatDesktopOffset(value));
            } catch (Throwable t) {
                logErr("desktop follow continuation failed", t);
                resetDesktopFollow();
                return;
            } finally {
                sDispatchingDesktopFollow.remove();
            }
        }
        scheduleDesktopFollowContinuation();
    }

    private static void resetDesktopFollow() {
        sDesktopFollowSpring.reset();
        sDesktopFollowTarget = null;
        sDesktopFollowMethodName = null;
        if (sDesktopFollowHandler != null) {
            sDesktopFollowHandler.removeCallbacks(sDesktopFollowContinuation);
        }
    }

    private void hookSceneRotationLifecycle(ClassLoader cl, String pkg) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.basesuperwallpaper.SuperWallpaper$SuperWallpaperEngine",
                    cl, "onWakeUp", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            sClassicAodPending = false;
                            if (sClassicAodActive) {
                                sClassicAodActive = false;
                                sRestoreLockOnWake = true;
                                sContinueAodRotation = false;
                                sSuppressWakeLockEvent = false;
                                log(pkg + ": legacy AOD wake -> restore lock scene");
                                return;
                            }
                            // onWakeUp 只代表从 AOD 回到锁屏，不代表已经进入桌面。
                            // 从唤醒到进入桌面期间持续抑制 Lock/ForceLock，避免静止帧被重置。
                            sContinueAodRotation = ModuleSettings.continueAodRotation();
                            sSuppressWakeLockEvent = sContinueAodRotation;
                            log(pkg + ": onWakeUp -> lock renderer continuation="
                                    + sContinueAodRotation + " suppressWakeLockEvent="
                                    + sSuppressWakeLockEvent);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!sRestoreLockOnWake) {
                                return;
                            }
                            try {
                                Object service = XposedHelpers.getObjectField(
                                        param.thisObject, "this$0");
                                String methodName = "com.miui.miwallpaper.earth".equals(pkg)
                                        || "com.miui.miwallpaper.moon".equals(pkg)
                                        ? "sendFilamentMessage" : "sendUnityMessage";
                                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    if (!sRestoreLockOnWake) {
                                        return;
                                    }
                                    try {
                                        XposedHelpers.callMethod(service, methodName, "Lock");
                                        log(pkg + ": classic AOD wake fallback Lock sent via "
                                                + methodName);
                                    } catch (Throwable t) {
                                        logErr(pkg + ": classic AOD wake fallback Lock failed", t);
                                    }
                                }, CLASSIC_AOD_WAKE_FALLBACK_MS);
                            } catch (Throwable t) {
                                logErr(pkg + ": classic AOD wake fallback scheduling failed", t);
                            }
                        }
                    });
            log(pkg + ": onWakeUp rotation lifecycle hooked");
        } catch (Throwable t) {
            logErr(pkg + ": onWakeUp rotation lifecycle hook failed", t);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.basesuperwallpaper.SuperWallpaper$SuperWallpaperEngine",
                    cl, "onKeyguardGoingAway",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            sContinueAodRotation = false;
                            sSuppressWakeLockEvent = false;
                            sClassicAodPending = false;
                            sClassicAodActive = false;
                            sRestoreLockOnWake = false;
                            sKeyguardGoingAwayUptime = android.os.SystemClock.uptimeMillis();
                            log(pkg + ": onKeyguardGoingAway -> allow OEM renderer pause lifecycle");
                        }
                    });
            log(pkg + ": onKeyguardGoingAway rotation lifecycle hooked");
        } catch (Throwable t) {
            logErr(pkg + ": onKeyguardGoingAway rotation lifecycle hook failed", t);
        }
    }

    /**
     * 开关开启时，统一拦截基类可见性、延迟消息和唤醒路径最终汇聚到的
     * unityPause/filamentPause；雪山当前版本的 Filament 暂停入口被混淆为 h()。
     * 不区分锁屏、AOD、Doze 或 Doze Suspend。
     * 这就是“尽人事”：native/SurfaceFlinger 等未经过这些 Java 方法的暂停不在覆盖范围内。
     */
    private void hookRendererPause(ClassLoader cl, String pkg, String methodName) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.basesuperwallpaper.SuperWallpaper", cl,
                    methodName,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!ModuleSettings.continueAodRotation()) {
                                return;
                            }
                            log(pkg + ": " + methodName + " blocked (all renderer pause gates)");
                            param.setResult(null);
                        }
                    });
            log(pkg + ": " + methodName + " lock continuation hook hooked");
        } catch (Throwable t) {
            logErr(pkg + ": " + methodName + " lock continuation hook failed", t);
        }
    }

    /**
     * onWakeUp() 会先 resume renderer，再发送 Lock/ForceLock。引擎已被系统暂停时，
     * resume 的第一帧会按累计时间推进，导致 AOD 静帧在 Lock/ForceLock 门控前跳变。
     * 仅在 AOD 唤醒后仍停留锁屏的窗口拦截；进入桌面时 onKeyguardGoingAway()
     * 先清除窗口标志，随后原厂 resume/Desk 路径照常执行。
     */
    private void hookWakeRendererResume(ClassLoader cl, String pkg, String methodName) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.basesuperwallpaper.SuperWallpaper", cl,
                    methodName,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (sSuppressWakeLockEvent
                                    && ModuleSettings.continueAodRotation()) {
                                log(pkg + ": wake " + methodName
                                        + " suppressed (preserve AOD static frame)");
                                param.setResult(null);
                            }
                        }
                    });
            log(pkg + ": " + methodName + " static wake frame hook hooked");
        } catch (Throwable t) {
            logErr(pkg + ": " + methodName + " static wake frame hook failed", t);
        }
    }

    /**
     * 可见性变为 false 是原厂安排延迟暂停的上游入口。实验开启时保留引擎的可见状态，
     * 让后续真正的 pause 入口仍有机会被统一拦截；可见性恢复为 true 仍走原厂逻辑。
     */
    private void hookVisibilityPauseGate(ClassLoader cl, String pkg) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.basesuperwallpaper.SuperWallpaper$SuperWallpaperEngine",
                    cl, "onVisibilityChanged", boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            boolean visible = (Boolean) param.args[0];
                            Context context = null;
                            Object wallpaper = null;
                            try {
                                wallpaper = XposedHelpers.getObjectField(param.thisObject, "this$0");
                                context = (Context) wallpaper;
                            } catch (Throwable ignored) {
                            }
                            if (!visible
                                    && ModuleSettings.continueAodRotation()) {
                                log(pkg + ": onVisibilityChanged(false) blocked (all renderer pause gates)");
                                param.setResult(null);
                            }
                        }
                    });
            log(pkg + ": onVisibilityChanged pause gate hooked");
        } catch (Throwable t) {
            logErr(pkg + ": onVisibilityChanged pause gate hook failed", t);
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

    private void hookReturnTrueWhenForceFullAod(ClassLoader cl, String className,
                                                String methodName, String tag,
                                                Class<?>... parameterTypes) {
        try {
            XposedHelpers.findAndHookMethod(className, cl, methodName,
                    callbackArgs(parameterTypes, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (ModuleSettings.forceFullAod()) {
                                param.setResult(true);
                            }
                        }
                    }));
            log(tag + ": hooked -> controlled by force full AOD setting");
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

    // 桌面->锁屏的 Lock 动画需要先完成，再允许原厂首个 pause；之后 pause 仍按上面的
    // Doze/锁屏状态门控处理。仅操作已有 Handler，不创建常驻线程或修改刷新率。
    private static void rescheduleRendererPause(Object superWallpaper) {
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
            ((Handler) handler).sendEmptyMessageDelayed(1, 2000L);
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
