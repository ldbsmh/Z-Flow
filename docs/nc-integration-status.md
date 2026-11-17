# NC 引擎接入 Z-Flow：功能状态总结

> 截至 2026-11-17（`import` 分支，本轮精简后）。技术细节见 [nc-window-engine.md](nc-window-engine.md)，
> 本文只回答一个问题：**哪些功能已经接入，哪些没有，为什么。**

## 0. 背景速览

Z-Flow 的小窗内核**固定为 NC 引擎**（设置 → 启动与触发 → 桌面与手势 → NC 引擎总开关）。
2026-11 精简时删掉了「小窗内核」选择器：经典虚拟屏内核只在引擎不可用/总开关关闭时
作为内部兜底，不再有外观可调项，因此对应的设置页条目也一并删除了。

| | 经典虚拟屏（仅内部兜底） | NC 引擎（NativeComplements，默认） |
|---|---|---|
| 窗口本质 | 虚拟屏 + 应用内自绘窗口 | **真实 freeform 任务**（WMS 层接管） |
| 运行位置 | Z-Flow 自己的进程 | system_server（窗口引擎）+ SystemUI（控件层） |
| 最近任务 | 不出现 | 真实任务，可配置隐藏 |
| 加载方式 | — | LSPosed 模块，改代码后需重载 SystemUI |

引擎与 Z-Flow 的通信通道：

1. **指令通道**：`io.relimus.zflow.COMMAND` 广播（OPEN / CONTROL / MINI），生命周期与
   行为开关（自动迷你、结束应用、贴边保活、横屏分享、隐藏最近任务）随 OPEN 下发；
2. **设置镜像通道**：引擎原生 `corner` 偏好由 Z-Flow 设置页镜像写入，改完发
   `SETTINGS_CHANGED` 广播，SystemUI 宿主重拉快照。

**外观（尺寸/迷你比例/变暗/贴边样式）不随请求下发**：引擎窗口支持长按调整大小，
几何由引擎自己的默认值决定。原本对应这些键的设置页条目（含整块「小窗外观与尺寸」）
已在本轮一并删除，避免“调了没反应”。

## 1. 已接入且真机验证通过 ✅

### 1.1 开窗入口（全部收口到 `FreeformRequestHandler` → `NcWindowBackend`）

| 入口 | 说明 |
|---|---|
| 桌面长按图标开小窗 | `popup_freeform_enable`，HookLauncher → `SOURCE_POPUP` |
| **多任务卡片上滑开小窗** | HookSwipeGesture → `SOURCE_RECENT_SWIPE`，携带被上滑卡片的 `taskId`；引擎从最近任务认领，按包名校验防任务号复用 |
| **通知栏小窗按钮** | `notify_freeform`，HookNotificationAction → `SOURCE_NOTIFICATION`；`openNotification` 保留通知的着陆 Activity；**入口受 Z-Flow 通知白名单（Room `notification_apps`）约束，与经典内核共用同一条链路** |
| 侧边栏快捷应用 | Z-Flow 自己的悬浮侧边栏选应用后走统一链路 |
| 开放 API | `open_api_zh-Hans.md` 的两个入口，`FreeformService` 里有 NC 分支 |

### 1.2 窗口形态与交互

| 功能 | 说明 |
|---|---|
| **出生即迷你** | 卡片上滑开的窗第一次布局就是迷你尺寸，没有「先默认窗 1 秒再缩」 |
| 迷你窗收尾 | `settleAsMini()`：失焦、重算输入窗口、恢复焦点任务；日志锚点 `MINI_SETTLED bar=0.0` |
| 无暗区 / 可触控 | 入场收尾按最终形态取 `barAlpha`（迷你=0），出生即迷你的会话不建 dim 层 |
| 单击还原 / 双击全屏 / 长按调整大小或静音 | 迷你窗三手势，引擎自带 |
| 贴边把手 = 应用图标 | 60dp 白底圆角 + 居中应用图标，按包名缓存 |
| 关闭 = 滑到底渐隐 | 底部 15% 为关闭区，窗口渐隐缩到 35%，松手按进度过半判定 |
| 引擎安装与恢复 | 首次开窗把总开关投影成 PM 组件状态；崩溃后 `recover()` 重挂会话 |

### 1.3 基础设施

- **LSPosed 双入口**：`ZFlowModule`（libxposed API 101，`java_init.list`）+
  `MainHook.dispatch()`（经典入口）。踩过的坑：APK 里有 `module.prop` 时 LSPosed
  忽略 `assets/xposed_init`，经典入口静默失效。排查：`adb logcat -s ZFlowMini`。

## 2. 已接入、待真机复验 🧪

代码完成、编译安装通过（**改引擎代码后要重载 SystemUI 才生效**）：

| 功能 | 验证点 |
|---|---|
| **多迷你窗并存** | 连开多个小窗同时挂着（`pinned` 列表），向下堆叠错开（`MINI_CASCADE`）；数量上限为引擎默认 2 |
| 贴边图标 / 滑底渐隐 | 贴边是应用图标；拖到底渐隐、无叉号 |
| 原生设置镜像 6 项 | `nc_*` 开关改完广播给 SystemUI 立即重拉；关「引擎总开关」后开窗回退经典内核 |
| **本轮扇区删除后的引擎** | 角落斜滑不再呼出任何菜单；小窗控制条、迷你窗、贴边、通知开窗全部正常（回归） |

## 3. 已接入的设置项

### 3.1 引擎原生设置 → `corner` 镜像 + `SETTINGS_CHANGED` 广播

| 设置页键 | 引擎键 | 默认 | 作用 |
|---|---|---|---|
| `nc_engine_enabled` | `enabled` | 开 | 引擎总开关：关掉后 Z-Flow 回退经典内核，控制条停止 |
| `nc_auto_minimize` | `autoMinimizeOnOpen` | 开 | 开新窗时上一个默认窗收成迷你（否则直接关闭） |
| `nc_end_on_close` | `endOnClose` | 关 | 关掉应用的最后一个窗口时 force-stop 该应用 |
| `nc_keep_edge_awake` | `keepEdgeAwake` | 关 | 贴边迷你窗保活应用进程 |
| `nc_double_close` | `doubleClose` | 关 | 双击空白处才关闭（默认单击） |
| `nc_hide_window_recents` | `hideRecents` | 关 | 小窗任务不进最近任务（与「隐藏 Z-Flow 自己」是两回事） |
| `nc_landscape_share_window` | `landscapeShareWindow` | 开 | 横屏下分享目标以窗口打开 |

小窗黑名单（Room）仍由 Z-Flow 侧 `isBlacklistedOrBlock()` 拦在开窗前，对两个内核一视同仁。

### 3.2 外观类设置：条目已删除

`freeform_size` / `freeform_size_land` / `freeform_float_view_size` /
`freeform_dimming_amount` / `freeform_dock_style` / `freeform_scale` /
`manual_adjust_freeform_rotation` / `ime_double_tap_retrigger`，连同
`max_freeform_windows` 与「小窗内核」选择器——这批设置页条目已整体删除，
整块「小窗外观与尺寸」页面也不再存在。

原因：NC 引擎不消费它们（窗口长按即可调整大小，几何用引擎默认值：竖屏 75%、
横屏 90%、迷你 28%、dim 20%、把手完全露出），留着就是“调了没反应”。
经典内核作为兜底仍会读这些键，读不到就走默认值，不影响开窗。

横屏应用名单（`landscape_apps`）保留：它决定“哪些应用以横屏小窗打开”，仍有意义。

## 4. 本轮精简：已删除的 NC 功能 ❌

用户决定「只保留 NC 小窗引擎，删除与 Z-Flow 高度拟合的部分」：

| 删除项 | 理由 |
|---|---|
| **角落扇形菜单全套**（FanView/HotspotView/CornerGesture/快捷操作面板） | 斜向滑动呼出的应用扇区与 Z-Flow 的轮盘（在 Z-Flow 项目的**另一个分支**实现）高度拟合；连同扇区专属的快捷操作（Wi-Fi/免打扰/截屏/录屏/亮度/音量/媒体控制，`WindowQuickHost` + `QuickActionCatalog`）、热区参数（`Config` 里 heat/distance/radius/icon/fanCount 等 10 项）、`LauncherGuard`（Pixel Launcher hook）一并移除 |
| **外观设置 8 项接入**（`WindowSettings` Bundle 通道、`SETTINGS` 指令、`dockStyle`/`maxWindows` extra），以及设置页里对应的 8 个条目 | 引擎已有长按调整大小；尺寸/比例/变暗/贴边样式交回引擎默认值，设置项留着也是假开关 |
| **通知点击接管**（`NotificationWindow`，从未安装的死代码） | Z-Flow 自己的通知钩子（带白名单）已覆盖 |
| **录屏 QS 磁贴适配**（`WindowTools`） | 只服务于扇区的「录屏」快捷操作 |
| **长按返回手势死代码**（`LongBackGesture`/`BackSwipe`/`BackHandoffState`） | 引擎内零引用（上游管理器侧功能） |
| **演示/诊断 Activity**（`DemoActivity`/`DiagnosticActivity`/`TaskTools`） | 无 manifest 声明的死代码 |
| **黑名单 → `corner.blocked` 镜像** | `blocked` 只被扇区消费，扇区已删 |

保留与 Z-Flow 互补的引擎内部能力：`LinkedBack`（小窗根返回路由）、`BackVisual`
（返回动画预览）、`ShareWindow`（分享开小窗）、`ServerViewport`（freeform 任务
viewport 一致性）、`WindowContinuation`（贴边保活）。

**「双击底部中心唤出键盘」**：上游 NC 的该项功能在移植时就未并入（`ProductFeatures`
空实现），当前代码里不存在，无需删除。

## 5. 未实现——可以做到，但本阶段没做 ⏸

| 功能 | 原因 / 备注 |
|---|---|
| 尺寸类设置的就地重排 | 已打开的窗口不跟随设置变化；如需支持要引擎加「按新几何重算当前会话」的遍历 |
| 引擎安装失败的自动回退 | `SunWindowModule` 预检不过时开窗会静默走经典兜底；「小窗内核」选择器已删，没有手动切换入口 |
| 数量上限的设置接入 | 引擎固定默认 2 个（`maxWindows` 字段保留，extra 通道已删）；`max_freeform_windows` 设置项已删除，经典内核回退时取默认 2 |
| `manual_adjust_freeform_rotation` | 引擎有旋转门控（`RotationGate`）但没有「手动调整」入口，设置项已删除 |

## 6. 不能实现——引擎架构层面无对应能力 ❌

| Z-Flow 设置 | 为什么做不到 |
|---|---|
| `freeform_scale`（DPI 缩放） | 引擎接管的是真实 task，DPI 由系统配置决定；旧实现自己也不生效 |
| `ime_double_tap_retrigger` | 旧实现底栏的 IME 方案，NC 引擎没有底栏，IME 完全交给系统；设置项已删除 |

**与内核无关、不适用搬运的**（两内核行为相同）：`hide_from_recent`（隐藏的是
Z-Flow 自己）、`popup_bubble_block`（通知气泡拦截）、`trusted_callers`、保活
`service_type`、悬浮侧边栏本身（只有「选完应用开窗」这一步落到内核）。

## 7. 已知问题与风险

1. **引擎每秒 ~1.3 次 COMMAND/RESULT 自发自收**：自开机起持续，疑似 `tick()` 认为
   有卡住的 active 会话。尚未排查，可能影响新窗口准入判断。
2. **多迷你窗的焦点与输入分发**：`finishGroup` / `occupySlot` / `presentExisting`
   都为多窗改过，多窗并发是既有逻辑没覆盖过的路径，若出现「展开 A 时 B 变黑/点不动」，
   先抓 `adb logcat -s SunWindowDemo5 ZFlowMini`。
3. **镜像默认值约束**：`WindowEngineSettings.MIRROR` 的默认值必须与引擎 `Config`
   严格一致，否则「从没动过设置」的用户会在首次镜像时被改行为。
4. **广播定向**：`SETTINGS_CHANGED` 必须 `setPackage("com.android.systemui")`，
   接收方还按发送方 UID 校验是本应用。改这条链路时两边都要过。

## 8. 排查速查

```
adb logcat -s ZFlowMini SunWindowDemo5 NativeComplements

ZFlowMini      hook ready classes=3       ← LSPosed 双入口正常
ZFlowMini      open pkg=… task=… mini=…   ← Z-Flow 侧请求已发出
SunWindowDemo5 MINI_SETTLED task=… bar=0.0 ← 迷你收尾正常（bar 必须是 0.0）
SunWindowDemo5 MINI_CASCADE               ← 多个迷你窗已错开位置
```

一条 ZFlowMini 日志都没有 → 九成是 `java_init.list` 入口没起来，重载 SystemUI；
有 `open` 没有 `MINI_SETTLED` → 引擎侧收尾问题，看 SunWindowDemo5 的拒绝行。
