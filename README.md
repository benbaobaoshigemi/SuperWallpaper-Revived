# SuperWallpaper-Revived

面向 HyperOS 的 LSPosed 模块，用于调整小米超级壁纸、锁屏与 AOD 之间的协作方式。

项目的核心目标是把 AOD 交还给系统主题和息屏应用，同时保留超级壁纸在桌面、锁屏之间的原生转场。模块不替换壁纸资源，也不修改系统显示刷新率。

## 功能

### AOD 与锁屏

- 阻止超级壁纸接管主题 AOD，保留系统时钟或全屏 AOD 样式。
- 支持在缺少对应能力门的设备上启用“和锁屏样式一致”的全屏 AOD 入口。
- 桌面进入锁屏时保留超级壁纸转场；锁屏直接进入 AOD 时保持当前锁屏构图，避免重新触发场景动画。
- 可选禁用 AOD 到锁屏时的壁纸缩放。
- 可选复用原厂全屏 AOD 的壁纸压暗逻辑。压暗程度由 SystemUI 根据息屏亮度决定，模块不提供额外的固定百分比。

### 桌面与应用转场

- 可选复用 MIUI Home 的全局壁纸缩放状态，在打开和退出应用时触发超级壁纸的缩放动效。
- 提供设置页和作用域进程重启入口。

### 实验功能

“AOD 下持续渲染超级壁纸”会拦截模块能够覆盖的 Java 层暂停、不可见和唤醒重置路径，尽量延长超级壁纸在锁屏、AOD 和 Doze 状态下的渲染时间。

这不是对渲染持续性的保证。native、SurfaceFlinger、系统电源管理和厂商资源策略仍可能暂停渲染。模块不会创建额外渲染线程，也不会干预刷新率或显示模式。

## 支持场景

当前适配的场景包包括：

- 地球
- 火星
- 土星
- 月球
- 雪山
- 几何

雪山使用独立的混淆渲染入口，模块对其暂停方法做了单独适配。不同场景的动画、转场和暂停机制并不完全相同，因此同一功能在各场景上的表现可能存在差异。

## 工作原理

超级壁纸由独立场景包提供，主题管理器负责应用状态，`com.miui.aod` 和 `com.android.systemui` 负责 AOD 样式与壁纸合成。模块按进程边界进行 Hook：

1. 在主题管理器和 AOD 相关路径上，阻止超级壁纸接管 AOD 样式。
2. 在 SystemUI 中恢复全屏 AOD 能力门、壁纸压暗复用和 AOD 到锁屏的缩放控制。
3. 在超级壁纸场景进程中处理 `ForceLock`、`Lock`、暂停和唤醒路径，尽量保持正确的场景状态。
4. 在 MIUI Home 中识别应用打开、退出和桌面转场，复用场景自身提供的缩放事件。

模块只调用或拦截已有的系统和场景接口，不向壁纸 APK 注入新的资源或渲染引擎。

## 设备与环境

已验证环境：

| 项目 | 信息 |
| --- | --- |
| 设备 | 小米 14 Ultra（aurora） |
| 系统 | HyperOS / Android 14+ |
| 框架 | LSPosed，Xposed API 82+ |
| 场景 | 地球、火星、土星、月球、雪山、几何 |

其他小米设备可能使用相近的 Filament、Unity 或 MIUI 壁纸基类，但系统版本、场景 APK 和方法签名存在差异。未经过对应设备验证的行为应视为待验证。

## 安装与作用域

1. 从 [Releases](https://github.com/benbaobaoshigemi/SuperWallpaper-Revived/releases) 下载 APK，并在 LSPosed 中启用模块。
2. 为模块勾选以下作用域：
   - `com.miui.miwallpaper`
   - `com.miui.miwallpaper.earth`
   - `com.miui.miwallpaper.mars`
   - `com.miui.miwallpaper.saturn`
   - `com.miui.miwallpaper.moon`
   - `com.miui.miwallpaper.snowmountain`
   - `com.miui.miwallpaper.geometry`
   - `com.android.thememanager`
   - `com.miui.aod`
   - `com.android.systemui`
   - `com.miui.home`
3. 重启模块作用域内的进程，或重启设备。
4. 如果需要全屏 AOD，在系统的息屏样式设置中选择“和锁屏样式一致”。

模块设置页提供以下开关：

- 禁用 AOD 壁纸缩放
- AOD 壁纸压暗
- 全局壁纸缩放
- AOD 下持续渲染超级壁纸
- 重启受影响的作用域进程

LSPosed 没有面向普通模块的公开接口来自动修改最终作用域勾选状态。模块 Manifest 提供推荐作用域，但首次安装后仍需在 LSPosed 中确认。

## 从源码构建

环境要求：Windows PowerShell、JDK 17、Android SDK。SDK 至少需要对应的 Android platform 和 build-tools；Gradle 会从本机环境自动发现 SDK 和 JDK，也可以通过 `ANDROID_HOME`、`ANDROID_SDK_ROOT`、`JAVA_HOME` 指定路径。

```powershell
.\gradlew.bat :module:assembleRelease
```

未签名 APK 位于：

```text
module\build\outputs\apk\release\module-release-unsigned.apk
```

发布或安装前需要使用自己的签名密钥签名。Gradle 构建会同时编译 Java、Kotlin、Compose UI 以及 Miuix 依赖，能够生成完整的设置页。旧的 `module\build.ps1` 仅用于历史 Java-only 调试构建，不能作为完整 UI APK 的构建流程。

## 已知限制

- 不同 HyperOS 版本可能调整类名、方法签名或 AOD 状态机，Hook 失效时需要结合设备日志重新适配。
- “AOD 下持续渲染超级壁纸”只能覆盖 Java 层可见的暂停路径，不能绕过 native、SurfaceFlinger 或系统电源管理的最终决策。
- 某些场景的转场动画由原厂脚本和引擎自行控制，动画长度、暂停时机和恢复行为可能不同。
- 应用转场缩放依赖场景本身支持对应事件；不支持的场景不会产生额外动画。
- 模块不会提高壁纸源资源的分辨率，也不会自动替换模型、贴图或材质。
- LSPosed 的作用域最终需要用户手动确认，模块不能代替 LSPosed 修改该状态。

## 关于 AOD 动画

部分超级壁纸具备独立的动画循环。理论上可以让它们在 AOD 中继续推进，但实际效果取决于壁纸 Surface 是否仍被系统调度、场景引擎是否接受 AOD 状态以及系统电源管理是否暂停进程。

当前实验开关的策略是尽量放行已有渲染路径，同时不修改刷新率、不创建常驻渲染线程。系统允许时，动画可能在 AOD 中继续一段时间；系统暂停后，模块不会强行恢复。功耗、温升、低刷新调度和不同场景的兼容性仍需在具体设备上单独验证。

## 许可证与免责声明

本项目基于对设备内置软件行为的逆向分析，仅用于个人学习和研究。项目不包含 MIUI、小米系统或其他厂商的专有代码与资源。使用者应遵守所在地法律、设备软件许可和相关服务条款，并自行承担使用风险。

本项目采用 [Apache-2.0](LICENSE) 许可证。
