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

### 出生即迷你（不要先默认窗再缩）

`queueMini()` 那条路是先按默认窗起来、入场结束再发 `MINI`，用户会看到一个完整默认窗
约 1 秒后再缩成迷你窗。现在改成在**第一次布局之前**就把形态定好：

```java
boolean startMini = i.getBooleanExtra("mini", false);
if (startMini) s.frame = "mini";   // 必须早于 prepareGeometry()
prepareGeometry(s);                // 它的 s.placement 依赖 s.frame
s.decoration = new TaskDecoration(a);
if (startMini) s.decoration.disableDim();
```

配套三件事：

| 位置 | 处理 |
|---|---|
| `prepareGeometry()` | `s.placement = "mini".equals(s.frame) ? s.miniPlacement : p`，所以形态必须先设 |
| `updateDecoration()` 的 PRESENTED 分支 | 入场收尾按形态取 `barAlpha`：迷你窗 0，默认窗 1。给 1 会同时亮出把手条和 dim 层 |
| `completeEntryInput()` | 迷你会话走 `settleAsMini()`：`setFocusable(false)` + `updateFocusedWindowLocked` + `resumeFocusedTasksTopActivities` + `requestTraversal` |

**踩过的坑：整屏变暗、背景点不动。** `barAlpha` 同时驱动把手条和 `TaskDecoration` 的 dim 层
（`alpha = dimAmount × barAlpha`，dim 覆盖整个 parent = 整屏）。默认窗几乎铺满屏幕，暗区被自己
盖住看不出来；迷你窗只占一小块，dim 整块露在外面。正常 `MINI` 指令路径有
`animate(startBar, endBar=0)` 把 `barAlpha` 收敛到 0，而出生即迷你没有这一步，于是暗区一直留着，
直到用户手动展开/缩小一次、重走 `animate()` 才恢复。

教训：`frame`、`barAlpha`、`dimEnabled` 是彼此耦合的形态状态。任何绕过 `state()` / `animate()`
的新开窗路径，都要把这些收尾动作自己补齐，或者从一开始就把它们设成目标形态的值。

排查（两端各一行锚点，不需要 LSPosed 日志）：

```bash
adb logcat -s ZFlowMini SunWindowDemo5
# ZFlowMini      open pkg=com.xxx task=123 mini=true      ← 请求从 Z-Flow 发出了
# SunWindowDemo5 OPEN_MINI task=123 pkg=com.xxx           ← 引擎收到了
# SunWindowDemo5 OPEN_TASK_FROM_RECENTS task=123 pkg=...  ← 从 recent 认领成功
# SunWindowDemo5 OPEN_TASK_REJECTED ...                   ← 任务号和包名对不上，拒绝
# SunWindowDemo5 MINI_SETTLED task=123 bar=0.0 ...        ← 迷你形态收尾完成（应为 bar=0.0）
# SunWindowDemo5 MINI_CASCADE task=124 x=... y=...        ← 第二个迷你窗已错开位置
# SunWindowDemo5 MINI_EVICT task=123 limit=2              ← 超出数量上限，最旧的小窗被收起
```

`MINI_SETTLED` 里的 `bar` 不是 0.0，就等于 dim 层还在，表现是整屏发暗。

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

## 贴边把手与关闭方式（对齐 Z-Flow）

这两处原本是 NativeComplements 自己的形态，并入后按 Z-Flow 的习惯改了：

| 位置 | 原实现 | 现实现 |
|---|---|---|
| 贴边后的把手 | 屏幕边一条 4dp×48dp 白条（`WindowControls` 里手绘） | Z-Flow 的把手外观：60dp 白色圆角底衬（`#F2FFFFFF`，圆角近半）+ 居中应用图标（占 80%）。图标按 `package` 缓存，在 SystemUI 进程用 `PackageManager.getApplicationIcon` 加载；失败只记 `EDGE_ICON_UNAVAILABLE`，退化成纯白底衬，不影响点击/拖动/还原 |
| 拖到底部关闭 | 复用 SystemUI 消息气泡的 `floating_dismiss_*` 圆形取消按钮，要把窗口拖进那个圆 | Z-Flow 的“滑到底部渐变消失”：关闭区是屏幕底部 15%，不画任何图标；窗口底边越进关闭区，`MiniMagnet` 进度越深，窗口渐隐（1→0.15）并缩到 35%，松手即关闭 |

`MiniMagnet` 从“圆形命中 + 二值”改成了“跟随一个连续目标值”：

```java
miniMagnet.follow((窗口底边 - 关闭区顶部) / 关闭区高度);  // 0..1
```

`progress()` 仍是 120ms 的平滑逼近，`latched()` 取 `progress >= 0.5`，所以“松手是否关闭”的
阈值语义与原来的按钮命中一致，只是不再需要瞄准。

拖动时窗口位置完全跟随手指，不再往关闭区中心吸附；因此补了 `clampMiniX/Y`，
保证窗口不会被拖出屏幕。

## 多个迷你窗与数量上限

引擎原来只有「前台窗 + 一个挂起的迷你窗」两个位置（`Session active` / `Session pinned`），
连开第二个应用时前一个会被关掉。现在 `pinned` 是列表，容量由 Z-Flow 的
「最多同时小窗数量」控制（`max_freeform_windows`，1~5，默认 2）。

数据通路只有一条，避免两个进程各自解释同一个设置：

```
Z-Flow 设置(SeekBar) → app_settings.max_freeform_windows
   → NcWindowBackend.maxWindows()          每次开窗现读，改设置立刻生效
   → Engine.openApp(..., maxWindows) → Wire  → OPEN 的 maxWindows extra
   → WindowEngine.maxWindows                超出时淘汰最旧的迷你窗
```

`WindowEngine` 侧的关键约定：

| 行为 | 实现 |
|---|---|
| 可见窗口 | `visible(s) = s == active 或 pinned.contains(s)` |
| 新迷你窗 | `retainPinned()`：追加到列表末尾（末尾 = 最近使用），超限时 `finishGroup` 最旧的一个，记 `MINI_EVICT` |
| 迷你窗展开 | `EXPAND_PINNED`：前台若是另一个迷你窗就留在 `pinned` 继续挂着，只有默认窗才让位 |
| 关闭窗口 | `finishGroup` 时若关的是前台，让最早挂着的迷你窗接替；同一任务组的其它迷你窗一并清理 |
| 任务退出 | `forget()` 先把会话从 `pinned` 摘掉，否则控制条会留在屏幕上 |

出生位置也要错开：所有迷你窗默认都放在 `miniTopRight()` 的同一个点，多个会完全重叠。
`cascadeMini()` 在首次布局（和每次收成迷你）时检测与已挂迷你窗的重叠，优先向下堆叠，
碰到安全区底部就换左边一列；用户拖动过的窗口不会被自动挪位。

SystemUI 侧从「一个 `miniControls`」改成「按 taskId 复用的 `WindowControls` 池」：
报文 `pinnedFrames`（`ParcelableArrayList<Intent>`）逐个更新，不在列表里的只 `hide()`；
贴边把手、IME 偏移、系统遮挡、`protectedRects` 都对全部实例聚合。旧引擎只发
`pinnedFrame`，宿主仍兼容单个的情形。

## 已知缺口（待后续阶段）

1. Z-Flow「小窗外观与尺寸 / 运行规则」里的尺寸比例、贴边样式、黑名单、横屏应用清单，
   目前只对新内核之外的旧实现生效；新内核用自身的几何与策略。数量上限已接通（见上一节）。
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
