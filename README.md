# SuperWallpaper-Revived · 把息屏还给主题，把动效还给锁屏

超级壁纸问世时，理念是超前的，效果是惊艳的：3D 实时渲染的月球跟着真实月相盈亏，地球在指尖缓缓转动——到今天，也很少有壁纸能在锁屏上复刻这种质感。

只是它没能跟上系统的脚步。新一代息屏（AOD）自成体系，超级壁纸却还按老规矩接管一切：一应用，AOD 被它抢走，桌面到锁屏的转场也成了生硬瞬切。这个 Xposed / LSPosed 模块，就是把这套超前体验接回今天的系统：**AOD 继续交给主题与息屏应用**，桌面 → 锁屏保留完整转场，锁屏 → AOD 保持静帧、不再刷新样式。

## 功能

- **AOD 不被超级壁纸接管**：屏蔽 `aod_using_super_wallpaper` 置位、吞掉 AOD 广播 / 命令 / 入口方法，AOD 始终显示当前主题样式（锁屏时钟或全屏 AOD）。
- **桌面 → 锁屏转场保留**：息屏路径原发 `ForceLock`（场景侧瞬移、硬切），模块按引擎状态改写为 `Lock`（摄像机插值动画），动画结束后停在锁屏视角，作为全屏 AOD 的静帧背景。
- **锁屏 → AOD 保持静帧**：样式变化只允许来自桌面的动画；从锁屏息屏时不重发场景动画、不重随机位相。
- **全屏 AOD 支持**：对缺失 `support_aod_fullscreen` 的设备（如小米 14 Ultra / aurora）强制启用“息屏样式 = 和锁屏样式一致”。
- **恢复「自定义」入口**：锁屏长按 → 自定义锁屏、系统个性化 → 超级壁纸主题的“自定义”按钮与编辑页预览刷新。
- **可选禁用 AOD → 锁屏缩放**：设置页开关会拦截超级壁纸引擎的 `WallpaperService.Engine.onZoomChanged`，只作用于 AOD/锁屏状态。
- **可选复用原厂全屏 AOD 压暗**：默认开启，沿用 SystemUI 的 `wallpaperBlack` 亮度曲线；关闭后跳过该压暗调用。
- **全局壁纸缩放**：跟随 MIUI Home 原厂壁纸缩放状态，在应用打开/退出时复用超级壁纸 `ZoomIn` / `ZoomOut` 动效。
- **AOD 下持续渲染**：实验性拦截可覆盖的 Java 暂停与不可见路径，尽可能让超级壁纸在 AOD / Doze 中继续渲染。
- **带 UI 的作用域管理**：显示声明的作用域、打开 LSPosed 设置，并在 root 可用时请求重启作用域进程。
- 覆盖 Filament 系（地球 / 月球）、Unity 系（火星 / 土星 / 几何）与雪山场景。

## 工作原理

超级壁纸由若干独立场景包（`com.miui.miwallpaper.{earth,mars,saturn,moon,snowmountain,geometry}`）提供，由 `com.android.thememanager` 下发“已应用超级壁纸”广播。息屏应用 `com.miui.aod` 收到后会把 AOD 样式切换为超级壁纸专用样式并接管渲染。

模块在各进程做最小拦截：

1. `thememanager`：屏蔽“超级壁纸已应用”广播，`aod_using_super_wallpaper` 保持 0。
2. `com.miui.aod` / `com.android.systemui`：强制开启全屏 AOD 能力门，恢复“和锁屏样式一致”选项与“自定义”入口。
3. 场景包（`SuperWallpaper` 基类）：息屏路径按引擎当前状态改写 `ForceLock` 事件——
   - 从桌面息屏 → 发 `Lock`，播放转场并顺延渲染暂停；
   - 从锁屏息屏（锁屏 → AOD）→ 丢弃 `ForceLock`，保持当前静帧；
   - 月球 / 地球场景额外拦截唤醒路径的 `ForceLock`，避免月球位相重随机、地球太阳构图按唤醒时刻重算导致的样式刷新（保持与 AOD 定格帧一致的构图）。

## 支持范围

| 项目 | 说明 |
| --- | --- |
| 设备 | 小米 14 Ultra（aurora），HyperOS / Android 14+（命令式息屏通知路径） |
| 场景 | 地球、火星、土星、月球、雪山、几何 |
| 框架 | LSPosed（Xposed API 82+） |

> 其它小米设备理论上可用（Filament / Unity 基类相同），但仅在本机验证过，请自行测试。

## 构建

环境：Windows PowerShell、JDK 17、Android SDK（build-tools、platform android-36）。工具链会自动从 `ANDROID_HOME` / `ANDROID_SDK_ROOT` / `JAVA_HOME` 发现，也支持常见默认安装路径。

```powershell
.\gradlew.bat :module:assembleRelease
```

产物：`module\build\outputs\apk\release\module-release-unsigned.apk`。发布包应使用 Gradle Release 产物签名后安装；该流程会包含 Kotlin/Compose 设置页和 Miuix 依赖。

源码构建不使用 `module\build.ps1`；该脚本仅保留给历史 Java-only 调试构建，不能生成完整设置页 APK。

## 安装与作用域

1. 在 LSPosed 中启用本模块并勾选以下作用域：
   - `com.miui.miwallpaper`
   - `com.miui.miwallpaper.earth` / `.mars` / `.saturn` / `.moon` / `.snowmountain` / `.geometry`
   - `com.android.thememanager`
   - `com.miui.aod`
   - `com.android.systemui`
   - `com.miui.home`
2. 重启作用域内进程（或重启系统）。
3. 设置 → 息屏 → 息屏样式：
   - 期望 AOD 显示锁屏时钟：保持默认息屏样式；
   - 期望全屏 AOD：勾选「和锁屏样式一致」。

模块内置设置页还提供：

- 禁用AOD壁纸缩放；
- AOD壁纸压暗（默认开启）；
- 全局壁纸缩放（默认开启）；
- AOD下持续渲染超级壁纸（实验性）；
- 使用 `su -c am force-stop` 请求重启上述作用域进程。

## 已知限制

- 全屏 AOD 的启用依赖对 `com.miui.aod` / `com.android.systemui` 的能力门 hook；不同 ROM 版本类名/方法可能有差异，失效时请提交 issue 附日志。
- 转场暂停顺延时长为固定值（2s），个别场景动画更长时可能被截断。
- LSPosed 没有供普通模块直接修改用户作用域勾选状态的公开 API；Manifest 的 `xposedscope` 会提供安装时的推荐作用域，最终勾选仍需用户确认。

## AOD 低频动画可行性

原厂全屏 AOD 的壁纸压暗与场景渲染是两条链路：SystemUI 通过 `wallpaperBlack` 调整壁纸合成亮度，而超级壁纸场景由独立的 Filament/Unity 引擎绘制。当前版本在保留主题 AOD 的策略下主动阻断超级壁纸 AOD 事件，并在息屏路径暂停 Filament，因此默认保持静帧。

地球、月球等场景本身具备 `Choreographer`/引擎动画循环，理论上可以在 AOD surface 仍由系统按低刷新率调度时恢复“每次 AOD 帧推进一次”的动画。但这需要同时验证 ROM 是否继续绑定该 wallpaper surface、AOD 的实际刷新回调频率、功耗/烧屏限制，以及各场景在 `AOD` 与 `LOCK` 状态间是否能无跳变恢复。基于当前反编译证据，这部分属于待设备实测的实验功能，本版本不默认开启。

## 免责声明

本项目源于对设备内置专有软件行为的逆向分析，仅用于个人学习研究，不包含任何 MIUI / 小米的代码或资源。请遵守所在地法律与相关软件许可，因使用本项目产生的任何后果由使用者自行承担。

## License

[Apache-2.0](LICENSE)
