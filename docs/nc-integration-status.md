# NC 引擎接入 Z-Flow：功能状态总结

> 截至 2026-11-17（`import` 分支 `2485190`）。技术细节见 [nc-window-engine.md](nc-window-engine.md)，
> 本文只回答一个问题：**哪些功能已经接入，哪些没有，为什么。**

## 0. 背景速览

Z-Flow 现在有两个可切换的小窗内核（设置 → 启动与触发 → **小窗内核**）：

| | 经典虚拟屏内核（原实现） | NC 引擎（NativeComplements，默认） |
|---|---|---|
| 窗口本质 | 虚拟屏 + 应用内自绘窗口 | **真实 freeform 任务**（WMS 层接管） |
| 运行位置 | Z-Flow 自己的进程 | system_server（窗口引擎）+ SystemUI（控件层） |
| 最近任务 | 不出现 | 真实任务，可配置隐藏 |
| 加载方式 | — | LSPosed 模块，改代码后需重载 SystemUI |

引擎通过两条通道与 Z-Flow 通信：

1. **指令通道**：`io.relimus.zflow.COMMAND` 广播（OPEN / CONTROL / MINI / SETTINGS），
   外观设置（尺寸、迷你比例、变暗、贴边比例）随 OPEN 请求下发；
2. **设置镜像通道**：引擎原生 `corner` 偏好由 Z-Flow 设置页镜像写入，改完发
   `SETTINGS_CHANGED` 广播，SystemUI 宿主重拉快照。

## 1. 已接入且真机验证通过 ✅

### 1.1 开窗入口（全部收口到 `FreeformRequestHandler` → `NcWindowBackend`）

| 入口 | 说明 |
|---|---|
| 桌面长按图标开小窗 | `popup_freeform_enable`，HookLauncher → `SOURCE_POPUP` |
| **多任务卡片上滑开小窗** | HookSwipeGesture → `SOURCE_RECENT_SWIPE`，携带被上滑卡片的 `taskId`；引擎从最近任务认领（`anyTaskForId` 兼容 recent），按包名校验防任务号复用 |
| 通知栏小窗按钮 | `notify_freeform`，HookNotificationAction → `SOURCE_NOTIFICATION`；`openNotification` 保留通知的着陆 Activity |
| 侧边栏快捷应用 | Z-Flow 自己的悬浮侧边栏选应用后走统一链路 |
| 开放 API | `open_api_zh-Hans.md` 的两个入口（应用选择界面 / 直接开小窗），`FreeformService` 里有 NC 分支 |

### 1.2 窗口形态与交互

| 功能 | 说明 |
|---|---|
| **出生即迷你** | 卡片上滑开的窗第一次布局就是迷你尺寸（`s.frame="mini"` 先于 `prepareGeometry`），没有「先默认窗 1 秒再缩」的过渡 |
| 迷你窗收尾 | `settleAsMini()`：失焦、重算输入窗口、恢复焦点任务；日志锚点 `MINI_SETTLED bar=0.0` |
| 无暗区 / 可触控 | 入场收尾按最终形态取 `barAlpha`（迷你=0），出生即迷你的会话不建 dim 层；已确认「暗区消失、可正常操作」 |
| 单击还原 / 双击全屏 / 长按调整 | 迷你窗三手势，引擎自带 |
| 引擎安装与恢复 | 首次开窗把总开关投影成 PM 组件状态；SystemUI 崩溃后 `recover()` 重挂会话 |

### 1.3 基础设施

- **LSPosed 双入口**：`ZFlowModule`（libxposed API 101，写 `java_init.list`）+
  `MainHook.dispatch()`（经典入口），`ClassLoaderProvider` 与 `scope.list` 齐备。
  ——踩过的坑：APK 里有 `META-INF/xposed/module.prop` 时 LSPosed 只读 `java_init.list`、
  忽略 `assets/xposed_init`，经典入口会静默失效。排查方法：`adb logcat -s ZFlowMini`，
  应有 `hook ready classes=3`。

## 2. 已接入、待真机复验 🧪

代码已完成、编译安装通过，但装完还没在真机上确认过表现（**改引擎代码后要重载
SystemUI 才生效**）：

| 功能 | 提交 | 验证点 |
|---|---|---|
| 贴边把手 = 应用图标样式 | `5ab12a8` | 贴边后是 60dp 白底圆角 + 应用图标；图标取不到时只剩白底（`EDGE_ICON_UNAVAILABLE`），不影响点击/拖动 |
| 关闭 = 滑到底渐隐 | `5ab12a8` | 拖窗到底部 15% 区域渐隐缩到 35%，松手按进度过半判定；不再有消息气泡叉号 |
| **多迷你窗并存** | `54066c2` | 连开多个小窗同时挂着（`pinned` 列表），向下堆叠错开（`MINI_CASCADE`） |
| 数量上限与淘汰 | `54066c2` | 超出 `max_freeform_windows`（1~5）时最旧的被收起（`MINI_EVICT`） |
| 外观设置 8 项 | `2a6c1ee` | 小窗大小 / 横屏大小 / 迷你比例 / 贴边露出比例在下次开窗生效；背景变暗改滑块立即生效 |
| 原生设置镜像 7 项 | `2485190` | `nc_*` 开关改完广播给 SystemUI 立即重拉；关「引擎总开关」后开窗回退经典内核 |
| 黑名单联动 | `2485190` | 加进小窗黑名单的应用，前台时角落扇区不再响应（`corner.blocked`） |

## 3. 已接入的设置项全表

### 3.1 Z-Flow 外观设置 → 随 OPEN 请求下发（`windowSettings` Bundle）

| 设置项 | 引擎落点 | 生效时机 |
|---|---|---|
| `freeform_size`（默认 75） | `Geometry.place()` 竖屏缩放 | 下次开窗 |
| `freeform_size_land`（默认 90） | `Geometry.place()` 横屏分支 | 下次开窗 |
| `freeform_float_view_size`（默认 20） | `miniTopRight()` 迷你比例（原固定 0.28，默认值下与原行为一致） | 下次开窗 |
| `freeform_dimming_amount`（默认 20） | dim 图层 alpha | **立即** |
| `freeform_dock_style`（默认 100） | 把手露出比例 | 下一帧 |
| `max_freeform_windows`（默认 2） | `maxWindows`，超限淘汰最旧 | 下次开窗 |
| 横屏应用名单（`landscape_apps`） | `s.landscape=true` 强制横屏几何 | 下次开窗 |
| 小窗黑名单（Room） | Z-Flow 侧拦在开窗前（引擎不参与开窗拦截） | 每次开窗 |

### 3.2 引擎原生设置 → `corner` 镜像 + `SETTINGS_CHANGED` 广播

| 设置页键 | 引擎键 | 默认 | 作用 |
|---|---|---|---|
| `nc_engine_enabled` | `enabled` | 开 | 引擎总开关：关掉后 Z-Flow 回退经典内核，SystemUI 扇区与控制条停止 |
| `nc_auto_minimize` | `autoMinimizeOnOpen` | 开 | 开新窗时上一个默认窗收成迷你（否则直接关闭） |
| `nc_end_on_close` | `endOnClose` | 关 | 关掉应用的最后一个窗口时 force-stop 该应用 |
| `nc_keep_edge_awake` | `keepEdgeAwake` | 关 | 贴边迷你窗保活应用进程 |
| `nc_double_close` | `doubleClose` | 关 | 双击空白处才关闭（默认单击） |
| `nc_hide_window_recents` | `hideRecents` | 关 | 小窗任务不进最近任务（与「隐藏 Z-Flow 自己」是两回事） |
| `nc_landscape_share_window` | `landscapeShareWindow` | 开 | 横屏下分享目标以窗口打开 |

黑名单每次增删改会同步一份包名集合到 `corner.blocked`（名单以 Room 为准）。

## 4. 未实现——可以做到，但本阶段没做 ⏸

| 功能 | 原因 / 备注 |
|---|---|
| **尺寸类设置的就地重排** | 已打开的窗口不跟随设置变化（会让窗口当场跳动）。想支持需引擎加“按新几何重算当前会话”的遍历，尚未做 |
| 通知点击接管（`notificationWindow`） | 引擎未安装 `NotificationWindow` Hook（死配置），Z-Flow 自己的通知按钮已覆盖此场景；硬接会一次点击开两个窗 |
| 引擎安装失败的自动回退 | `SunWindowModule` 预检不过时表现为「点了没反应」，需手动切回经典内核。可做成 open 失败自动降级 |
| `manual_adjust_freeform_rotation` | 引擎有旋转门控（`RotationGate`）但没有「手动调整」入口，需要引擎侧加能力 |
| 角落扇区几何参数（10 项） | 用户决定不搬：热区/半径/图标/数量/模糊/横竖屏禁用等按引擎默认值运行，设置页不出现 |
| 角落扇区快捷应用名单 | `apps` / `targets` 不进设置页，扇区面板内可编辑 |

## 5. 不能实现——引擎架构层面无对应能力 ❌

| Z-Flow 设置 | 为什么做不到 |
|---|---|
| `freeform_scale`（DPI 缩放） | 引擎接管的是真实 task，DPI 由系统配置决定；旧实现自己注释里也写了「实际跟随物理屏幕」，本来就不生效 |
| `ime_double_tap_retrigger` | 这是旧实现底栏的 IME 方案，NC 引擎没有底栏，IME 完全交给系统 |
| `longBackWindow`（长按返回键开小窗） | 上游是管理器侧的 Hook，引擎内零引用；搬过来需要另行在 Z-Flow 侧写返回键 Hook，与小窗内核无关 |

**与内核无关、不适用搬运的**（两内核行为相同）：`hide_from_recent`（隐藏的是
Z-Flow 自己）、`popup_bubble_block`（通知气泡拦截）、`trusted_callers`、保活
`service_type`、悬浮侧边栏本身（`show_floating` / `floating_position_x` /
`floating_alpha` / `quick_floating_app`——这些是 Z-Flow 自己的 UI，只有「选完应用
开窗」这一步落到内核）。

## 6. 与经典内核的有意差异

- 迷你窗是**真实任务**：最近任务里可见（可用 `nc_hide_window_recents` 关）、有真实
  taskId，第三方启动器也能识别；经典内核的虚拟屏窗口对系统不可见。
- 默认窗、迷你窗的比例数值由设置驱动（经典内核是硬编码 + 部分设置）。
- 关闭语义：迷你窗拖到底部渐隐关闭（经典内核是自己的关闭按钮）。
- 引擎代码更新后必须重载 SystemUI（重启系统界面或手机）才加载新代码；经典内核
  重装 Z-Flow 即生效。

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
SunWindowDemo5 OPEN_SETTINGS …            ← 引擎收到设置
SunWindowDemo5 MINI_SETTLED task=… bar=0.0 ← 迷你收尾正常（bar 必须是 0.0）
SunWindowDemo5 MINI_CASCADE / MINI_EVICT  ← 多窗错开 / 数量淘汰
```

一条 ZFlowMini 日志都没有 → 九成是 `java_init.list` 入口没起来，重载 SystemUI；
有 `open` 没有 `MINI_SETTLED` → 引擎侧收尾问题，看 SunWindowDemo5 的拒绝行。
