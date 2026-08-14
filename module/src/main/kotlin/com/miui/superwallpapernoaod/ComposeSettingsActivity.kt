package com.miui.superwallpapernoaod

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

class ComposeSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ModuleSettings.syncSecureSettings(this)
        setContent { SettingsScreen(this) }
    }
}

private val scopePackages = listOf(
    "com.miui.miwallpaper",
    "com.miui.miwallpaper.earth",
    "com.miui.miwallpaper.mars",
    "com.miui.miwallpaper.saturn",
    "com.miui.miwallpaper.moon",
    "com.miui.miwallpaper.snowmountain",
    "com.miui.miwallpaper.geometry",
    "com.android.thememanager",
    "com.miui.aod",
    "com.android.systemui",
    "com.miui.home",
)

@Composable
private fun SettingsScreen(activity: Activity) {
    val preferences = remember {
        activity.getSharedPreferences(ModuleSettings.PREFS_NAME, Activity.MODE_PRIVATE)
    }
    var disableZoom by remember {
        mutableStateOf(preferences.getBoolean(ModuleSettings.KEY_DISABLE_AOD_LOCK_ZOOM, false))
    }
    var reuseDimming by remember {
        mutableStateOf(preferences.getBoolean(ModuleSettings.KEY_REUSE_OEM_FULL_AOD_DIMMING, true))
    }
    var continueAodRotation by remember {
        mutableStateOf(preferences.getBoolean(ModuleSettings.KEY_CONTINUE_AOD_ROTATION, false))
    }
    var appTransitionZoom by remember {
        mutableStateOf(preferences.getBoolean(ModuleSettings.KEY_APP_TRANSITION_ZOOM, true))
    }
    var forceFullAod by remember {
        mutableStateOf(preferences.getBoolean(ModuleSettings.KEY_FORCE_FULL_AOD, true))
    }
    var status by remember { mutableStateOf("") }

    MiuixTheme {
        Scaffold(
            topBar = { SmallTopAppBar(title = "超级壁纸", subtitle = "息屏与锁屏") },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding(),
                ),
            ) {
                item {
                    SmallTitle("壁纸行为")
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        SwitchPreference(
                            title = "禁用AOD壁纸缩放",
                            summary = "保持息屏唤醒后的锁屏构图不被缩放",
                            checked = disableZoom,
                            onCheckedChange = {
                                disableZoom = it
                                ModuleSettings.save(activity, ModuleSettings.KEY_DISABLE_AOD_LOCK_ZOOM, it)
                            },
                        )
                        SwitchPreference(
                            title = "强制启用全屏AOD",
                            summary = "仅绕过机型能力限制；超级壁纸兼容始终保持开启",
                            checked = forceFullAod,
                            onCheckedChange = {
                                forceFullAod = it
                                ModuleSettings.save(activity, ModuleSettings.KEY_FORCE_FULL_AOD, it)
                            },
                        )
                        SwitchPreference(
                            title = "AOD壁纸压暗",
                            summary = "压暗程度跟随系统息屏亮度自动变化",
                            checked = reuseDimming,
                            onCheckedChange = {
                                reuseDimming = it
                                ModuleSettings.save(activity, ModuleSettings.KEY_REUSE_OEM_FULL_AOD_DIMMING, it)
                            },
                        )
                        SwitchPreference(
                            title = "全局壁纸缩放",
                            summary = "打开和退出应用时复用超级壁纸的缩放动效",
                            checked = appTransitionZoom,
                            onCheckedChange = {
                                appTransitionZoom = it
                                ModuleSettings.save(activity, ModuleSettings.KEY_APP_TRANSITION_ZOOM, it)
                            },
                        )
                        SwitchPreference(
                            title = "AOD下持续渲染超级壁纸",
                            summary = "拦截可覆盖的暂停路径，包含锁屏、AOD 与 Doze；系统或 native 路径仍可能自行暂停",
                            checked = continueAodRotation,
                            onCheckedChange = {
                                continueAodRotation = it
                                ModuleSettings.save(activity, ModuleSettings.KEY_CONTINUE_AOD_ROTATION, it)
                            },
                        )
                    }
                }
                item {
                    SmallTitle("立即生效")
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        ArrowPreference(
                            title = "重启并应用更改",
                            summary = status.ifEmpty { "重启受影响进程，使已保存的设置生效" },
                            onClick = { status = restartScopeProcesses() },
                        )
                    }
                }
                item {
                    SmallTitle("模块设置")
                    Card(
                        modifier = Modifier.padding(horizontal = 12.dp),
                    ) {
                        ArrowPreference(
                            title = "管理 LSPosed 作用域",
                            summary = "选择模块生效的系统与壁纸进程",
                            onClick = { openLsposedSettings(activity) },
                        )
                    }
                }
                item { Spacer(modifier = Modifier.height(12.dp)) }
            }
        }
    }
}

private fun restartScopeProcesses(): String {
    val failed = scopePackages.filter { packageName ->
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop $packageName")).waitFor() != 0
        } catch (_: Exception) {
            true
        }
    }
    return if (failed.isEmpty()) "已请求重启全部作用域进程。" else "重启失败：${failed.joinToString()}"
}

private fun openLsposedSettings(activity: Activity) {
    try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("intent:#Intent;package=org.lsposed.manager;end")))
    } catch (_: ActivityNotFoundException) {
        // The UI cannot install or open a manager that is not present.
    }
}
