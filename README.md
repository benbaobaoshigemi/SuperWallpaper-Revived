# PhoenixWallpaper · 把息屏还给主题，把动效还给锁屏

小米超级壁纸确实好看，可惜一应用，息屏（AOD）就被它接管，桌面到锁屏的转场也变成生硬的瞬切。这个 Xposed / LSPosed 模块把体验纠正回来：**AOD 继续交给主题与息屏应用**，桌面 → 锁屏保留完整转场，锁屏 → AOD 保持静帧、不再刷新样式。

## 功能

- **AOD 不被超级壁纸接管**：屏蔽 `aod_using_super_wallpaper` 置位、吞掉 AOD 广播 / 命令 / 入口方法，AOD 始终显示当前主题样式（锁屏时钟或全屏 AOD）。
- **桌面 → 锁屏转场保留**：息屏路径原发 `ForceLock`（场景侧瞬移、硬切），模块按引擎状态改写为 `Lock`（摄像机插值动画），动画结束后停在锁屏视角，作为全屏 AOD 的静帧背景。
- **锁屏 → AOD 保持静帧**：样式变化只允许来自桌面的动画；从锁屏息屏时不重发场景动画、不重随机位相。
- **全屏 AOD 支持**：对缺失 `support_aod_fullscreen` 的设备（如小米 14 Ultra / aurora）强制启用“息屏样式 = 和锁屏样式一致”。
- **恢复「自定义」入口**：锁屏长按 → 自定义锁屏、系统个性化 → 超级壁纸主题的“自定义”按钮与编辑页预览刷新。
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
powershell -ExecutionPolicy Bypass -File module\build.ps1
```

产物：`module\SuperWallpaperRevived.apk`（debug 签名，安装即可）。

## 安装与作用域

1. 在 LSPosed 中启用本模块并勾选以下作用域：
   - `com.miui.miwallpaper`
   - `com.miui.miwallpaper.earth` / `.mars` / `.saturn` / `.moon` / `.snowmountain` / `.geometry`
   - `com.android.thememanager`
   - `com.miui.aod`
   - `com.android.systemui`
2. 重启作用域内进程（或重启系统）。
3. 设置 → 息屏 → 息屏样式：
   - 期望 AOD 显示锁屏时钟：保持默认息屏样式；
   - 期望全屏 AOD：勾选「和锁屏样式一致」。

## 已知限制

- 全屏 AOD 的启用依赖对 `com.miui.aod` / `com.android.systemui` 的能力门 hook；不同 ROM 版本类名/方法可能有差异，失效时请提交 issue 附日志。
- 转场暂停顺延时长为固定值（2s），个别场景动画更长时可能被截断。

## 免责声明

本项目源于对设备内置专有软件行为的逆向分析，仅用于个人学习研究，不包含任何 MIUI / 小米的代码或资源。请遵守所在地法律与相关软件许可，因使用本项目产生的任何后果由使用者自行承担。

## License

[Apache-2.0](LICENSE)
