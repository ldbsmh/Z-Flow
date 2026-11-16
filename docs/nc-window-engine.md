# 小窗内核迁移：NativeComplements 引擎接入 Z-Flow

本次改动把小窗的“实现方式”从 Z-Flow 原有的虚拟屏方案换成 NativeComplements 的方案：

| | 原实现（legacy） | 新实现（nc） |
|---|---|---|
| 承载方式 | 每个小窗一个 `VirtualDisplay` + 独立任务栈 | 同屏真实 freeform 任务 |
| 窗口外观 | SystemUI 里自绘窗口框（`FreeformWindow`） | 系统窗口 + SystemUI 控件层（贴边图标、迷你窗、底栏关闭区） |
| 跨进程协议 | AIDL `IFreeformManager` | 广播 + Binder 帧通道（`Wire`，协议 16） |
| Hook 框架 | 经典 Xposed API 82 | libxposed API 101 |
| 入口 | 侧边栏 / 磁贴 / 通知 / 桌面长按 | 保留 Z-Flow 入口，另带 NC 自有的角落扇区 |

## 结构

```
Z-Flow/
├── ncengine/                     # 移植的 NativeComplements 小窗引擎（library，包名保持 dev.local.sunwindowdemo）
│   ├── libs/libxposed-api-101.0.1.jar
│   ├── libs/hidden-stubs.jar
│   ├── consumer-rules.pro        # R8：引擎全靠反射，必须整体 keep
│   └── src/main/
│       ├── java/.../features/floatingwindow/legacy/   # WindowEngine / SystemUiHost / WindowControls / Wire / Config ...
│       ├── java/.../core/                             # 后端资格与归属判定
│       ├── java/.../Engine.java                       # 对宿主暴露的唯一门面
│       ├── resources/META-INF/xposed/{java_init.list,module.prop,scope.list}
│       └── AndroidManifest.xml                        # CONTROL 权限、资格接收器、ConfigProvider
└── app/
    ├── src/main/java/io/relimus/zflow/service/NcWindowBackend.kt   # 后端选择与路由
    ├── FreeformRequestHandler.create()                            # 统一出口，改为按后端分流
    └── xposed/services/FreeformService.handleStartIntent()        # 同上
```

调用链：

```
侧边栏 / 磁贴 / 通知 / 桌面长按
   → FreeformRequestHandler.create() 或 FreeformService
   → NcWindowBackend（读 window_engine 偏好）
   → Engine.openApp / openNotification
   → 广播 io.relimus.zflow.COMMAND（发给 android 包 = system_server）
   → ncengine WindowEngine（真实 freeform 任务）
   → SystemUI 的 SystemUiHost + WindowControls（贴边图标、迷你窗、关闭区）
```

## 多任务卡片上滑开小窗

入口在 Z-Flow 的 Launcher 手势 hook（`HookSwipeGesture`），原本就存在；新内核要补的是
「拿一个已存在的任务号直接开成迷你窗」这一段：

```
HookSwipeGesture（最近任务里把卡片拖到投放区松手）
   → 广播 io.relimus.zflow.start_freeform（mini_mode=true，附被滑卡片的 taskId）
   → FreeformRequestHandler.create()
   → NcWindowBackend.openApp(pkg, miniMode=true, taskId)
   → Engine.openApp(pkg, taskId, mini=true)
   → 广播 io.relimus.zflow.COMMAND：op=OPEN，带 taskId + mini
   → WindowEngine.open()
```

引擎侧有三处配合，缺一条就表现为「松手后什么都没发生」：

| 位置 | 处理 | 为什么需要 |
|---|---|---|
| `taskForOpen()` | 先按 `MATCH_TASK_IN_STACKS_ONLY` 找，找不到再看 recent 列表（`MATCH_TASK_IN_STACKS_OR_RECENT_TASKS`），并要求 `realActivity` 包名一致 | 被上滑的卡片已经不在任何栈上，只存在于 recent；旧写法直接取到 `null` 就退出了 |
| `queueMini()` | 入场动画期间 `state()` 会因 `s.entering` 拒绝切换，所以先把 `MINI` 挂成 `entryControl`，由 `completeEntryInput()` 在入场结束后补发 | 打开动画还在跑时下达形态切换会被静默丢弃 |
| 已开窗的 `retained` 分支 | `presentExisting()` 之后同样走 `queueMini()` | 该应用已经有窗口时，不应该只是把它提到前台，而要收成迷你窗 |

排查（两端各一行锚点，不需要 LSPosed 日志）：

```bash
adb logcat -s ZFlowMini SunWindowDemo5
# ZFlowMini      open pkg=com.xxx task=123 mini=true      ← 请求从 Z-Flow 发出了
# SunWindowDemo5 OPEN_MINI task=123 pkg=com.xxx           ← 引擎收到了
# SunWindowDemo5 OPEN_TASK_FROM_RECENTS task=123 pkg=...  ← 从 recent 认领成功
# SunWindowDemo5 OPEN_TASK_REJECTED ...                   ← 任务号和包名对不上，拒绝
```

前两行有、后面没有，说明卡在准入（`AndroidWindowAdmission`）；一行都没有，说明手势
hook 没触发，与内核无关。

## 一个必须知道的坑：`java_init.list` 会顶掉 `assets/xposed_init`

并入 ncengine 后，APK 里多了 `META-INF/xposed/module.prop` + `java_init.list`
（libxposed API 101 的新模块元信息）。**LSPosed 看到 `module.prop` 就按新版模块处理，
只读 `java_init.list`，完全忽略 `assets/xposed_init`。**

于是出现过一次「什么都没坏，但什么都不工作」：

| 现象 | 真相 |
|---|---|
| 小窗引擎正常（`SunWindowDemo5 MODULE_LOADED` / `GUARD_READY`） | ncengine 的三个入口在 `java_init.list` 里，照常加载 |
| 上滑卡片没有提示、松手不开小窗 | `MainHook` 在 `assets/xposed_init` 里，**函数根本没被调用** |
| `adb logcat` 一行 `ZFlow*` 都没有 | 连报错都没有，因为入口没执行 |

修法是给 Z-Flow 也加一个 API 101 入口 `io.relimus.zflow.xposed.hook.ZFlowModule`
（同样写进 `java_init.list`），生命周期回调转调共用的 `MainHook.dispatch(pkg, classLoader)`：

| 经典入口 | API 101 入口 |
|---|---|
| `initZygote(startupParam)` | `onModuleLoaded` + `XposedModule` 自身 |
| `handleLoadPackage(lpparam)`，`packageName == "android"` | `onSystemServerStarting(param)` |
| `handleLoadPackage(lpparam)`，其他包 | `onPackageLoaded(param)` |

`ZFlowModule` 还顺带补了 ezxhelper 的 `ClassLoaderProvider.classLoader`：
hook 代码里用 `ClassUtil.loadClass(name)` 解析 ROM 内部类，不设置就回退到系统
ClassLoader，桌面里的类一个都找不到。

`scope.list` 里补了 `io.relimus.zflow`：否则 Z-Flow 自己进程里不挂 `HookMyself`，
首页会一直显示「LSPosed 没有激活」。

## 开关

设置 → 启动与触发 → 桌面手势 → **小窗内核**

- `NativeComplements 内核（真实 freeform 任务）`：默认值。
- `经典虚拟屏内核（原实现）`：回退用，行为与改动前一致。

首次以新内核开窗时，会把引擎侧总开关（`corner.enabled`）同步为开启，
否则 SystemUI 控件层不会绘制。

## 已并/未并的上游能力

并入：`WindowEngine`、`SystemUiHost`、`WindowControls`、`Wire`、`Config`、`TaskCover`、
`TaskVisibilityGuard`、`WindowContinuation`、`LinkedBack`、`ServerViewport`、`ShareWindow`、
`LauncherGuard`（角落扇区）、`BackBridge`（SystemUI 宿主）。

未并（Z-Flow 已有对应入口或本阶段不做）：

- 通知点击接管（`NotificationWindow`）——由 Z-Flow 自己的通知钩子负责，叠加会一次点击开两个窗。
- 弹幕通知、快捷建议、边缘防误触、图标适配、来电拦截、壁纸等非小窗功能。
- 上游 Compose 设置页（`features/floatingwindow/ui/**`）——设置界面仍用 Z-Flow 的 Preference 页。

## 已知缺口（待后续阶段）

1. Z-Flow「小窗外观与尺寸 / 运行规则」里的尺寸比例、贴边样式、数量上限 1~5、黑名单、
   横屏应用清单，目前只对新内核之外的旧实现生效；新内核用自身的几何与策略。
2. 引擎安装失败（例如 `SunWindowModule` 的 `startOtherServices` deopt 预检不过）时不会自动回退，
   表现为“点了没反应”，需要在设置里手动切回经典内核。
3. NC 自带的角落扇区随总开关一起启用，尚未提供单独的 Z-Flow 开关。
4. 真机未验证：`Wire` 协议、`WindowEngine` 的 WMS Hook、SystemUI 宿主在当前 ROM 上的可用性。

## 构建

```
./gradlew :app:assembleDebug      # 产出 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease    # R8 + 未签名（无 keystore.properties 时）
```

构建期已验证：`java_init.list` / `module.prop` / `scope.list` 进入 APK，
`dev.local.sunwindowdemo.WindowEngine` 在 dex 中，合并清单里包含
`io.relimus.zflow.CONTROL`、`io.relimus.zflow.settings`、资格接收器。

release 侧注意：R8 已通过（`minifyReleaseWithR8` 成功，混淆后类名仍保留）；
`assembleRelease` 目前会被 `lintVitalRelease` 挡住，原因是仓库既有的
`drawable-night/ic_alipay.xml`、`ic_paypal.xml`、`ic_wechat_pay.xml` 缺少基础版本
（`MissingDefaultResource`，与本次改动无关）。临时产出未签名包可用
`./gradlew :app:assembleRelease -x lintVitalRelease`。
