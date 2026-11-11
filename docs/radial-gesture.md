# 触发区上滑轮盘

从屏幕底角的**触发区域**里按下并斜向上滑，扇形展开最多 13 个应用（三环 3+4+6），
滑到目标松手即以小窗打开。

## 交互规则

| 阶段 | 判定 | 说明 |
|---|---|---|
| 起手 | 按下点落在用户配置的触发区内（默认左下角 20%×12%，可镜像到右侧） | 超出区域一律按原有手势处理 |
| 展开 | 位移 > `touchSlop`，**垂直位移朝上且超过半个阈值**，且水平分量不超过垂直分量的 2.5 倍 | 只认「斜向内上滑」，沿底边横扫不会触发 |
| 选择 | 最近格心 + 进入半径，带 0.62×圆盘直径的迟滞 | 手指滑出弧线外仍可继续选，边界不抖 |
| 提交 | 松手时取选中项 | 未选中任何一格则不打开，退回 Launcher 原本行为 |

- 开关：设置 → 启动与触发 → 桌面与手势 → 上滑轮盘（默认关闭，见 `settings.xml`）。
- 触发区域：同页「触发区域」进入编辑页，四个滑杆（横/纵位置、宽、高）都是屏幕百分比，
  另有「镜像到另一侧」开关。编辑页同时给两层预览：
  - 页内的手机轮廓预览（任何设备可用，按真实屏幕比例缩放）；
  - 有悬浮窗权限时，再把这台区域的**真实坐标**叠加显示在屏幕上（一比一）。
- 圆心跟着触发区中心走：把触发区挪到哪，轮盘就从哪展开；
  扇形始终朝屏幕内上方张开，靠屏幕中心的哪一半决定开向左侧还是右侧。
- 轮盘离指距离：内环半径下限 108dp，白色圆盘整体「浮」在触发区上方一段距离，
  而不是挤在手指边上（之前贴得近，手指刚动就压在图标上）。
- 名单：设置 → 启动与触发 → 桌面与手势 → 轮盘应用（最多 13 个，**勾选顺序即轮盘排列顺序**）。
- 展开期间不再显示原有的落点提示：两套浮层同时出现会让用户不知道松手会发生什么。
- 展开期间 Launcher 的输入被**整段吃掉**（DOWN/MOVE/UP/CANCEL 都拦），
  否则最近任务卡片会和轮盘一起出现。
- **吃掉事件就必须给出终态**，这是硬约束，不是优化项：见下面「为什么不能让手势悬着」。
- Android 17 上 `ACTION_UP` 不一定送达输入 Hook。因为 DOWN 已经被拦下，
  Launcher 根本不会创建 `AbsSwipeUpHandler`，手势结束回调也就不会触发 ——
  所以 `ACTION_UP` 这里没有兜底，必须靠看门狗（见下）。

## 为什么不能让手势悬着

轮盘在 `ACTION_DOWN` 就把事件拦下，这是必须的（否则最近任务卡片会和轮盘一起出现）。
代价是：**Launcher 从这一刻起再也收不到这条手势的任何事件**，而它注册在
system 侧的 `recents_animation_input_consumer` 一直在等后续 MotionEvent。

一旦我们拦了 DOWN 却迟迟不给终态（UP 丢失、方向判定迟迟不落地、上一轮的悬浮窗没摘干净），
InputDispatcher 等满 5 秒就报：

```
am_anr: [0,<pid>,system,...,Input dispatching timed out
        (recents_animation_input_consumer is not responding. Waited 5001ms for MotionEvent).]
```

注意 ANR 记在 `system` 进程上（consumer 注册在 system 侧），所以用户看到的是
**「进程 system 没有响应」**，而不是「桌面没有响应」——这也是它看起来跟轮盘没关系的原因。

四条独立的出口保证「悬着」不会发生：

| 出口 | 触发条件 | 行为 |
|---|---|---|
| 方向否决 | 位移越过阈值但不满足「朝上 + 在扇形内」 | 立刻 `clearRadialState()` 并**放行该 MOVE** |
| 新手势清场 | 任何 `ACTION_DOWN` 发现上一轮还 active / 悬浮窗还在 | 先清场再重新判定 |
| 无主悬浮窗 | 收到 MOVE 时悬浮窗还在但 gate 已 idle | 摘掉悬浮窗并放行，不吞事件 |
| 看门狗 | 未展开 2 秒 / 已展开 6 秒没有任何新事件 | 清场（只清理，不提交选择） |

看门狗超时只清理、不提交：输入流已经断了，用户并没有完成「滑到目标再松手」，
这时替他启动一个应用属于误操作。


## 桌面为什么会跟着手指动（输入独占）

TIS（`TouchInteractionService`）和桌面窗口之间是**监视**关系，不是消费关系：
`InputMonitor` 只是拿到一份事件副本，桌面自己的窗口**同时**也在收同一串事件。
平时不冲突，因为 TIS 决定处理手势时会调 `pilferPointers()`，由 InputDispatcher
给桌面补一个 `ACTION_CANCEL`，后续事件独家转给监视方。

我们在 `TouchInteractionHandler.onInputEvent` 入口把方法短路掉，那条 `pilferPointers()`
就永远不会执行 —— 于是出现了「轮盘正常，但在轮盘上滑动挑选时，后面的工作区跟着左右翻页」。

修法是两层：

| 层 | 做法 | 作用 |
|---|---|---|
| 主 | `InputPilfer`：沿对象图按方法名找到 `pilferPointers()`，反射调用 | InputDispatcher 给桌面发 CANCEL，桌面彻底退出这条手势 |
| 兜 | `Activity.dispatchTouchEvent` Hook：轮盘展开期间直接吞掉事件 | 反射随 ROM 变化失效时，视图树照样收不到 DOWN/MOVE |

时机很关键：**都在「确认是上滑」之后**（`shouldReveal` 通过那一刻），不在 DOWN。
如果在 DOWN 就抢，触发区里的普通点击（底部 Dock 图标）会一起失效 —— 那比轮盘本身更难忍受。

`InputPilfer` 刻意不写死字段名：`InputConsumerController` 的内部结构在
Android 版本之间换过好几次，所以从 `thisObject` 出发做 BFS（深度 ≤4、节点 ≤160），
按方法名找 `pilferPointers` / `notifyPilferPointers`，找到后缓存调用路径。
日志里会打一行 `pilfer=ok` 或 `pilfer=unavailable`，后者说明反射没找到入口、只靠兜底层在工作。


## 尺寸与排布

视觉基线对齐 GestureEVO 的圆形按钮：圆盘大、间隙均匀、逐层往外铺开。

| 参数 | 值 |
|---|---|
| 圆盘（白色圆底）直径 | 56dp，最小不低于 34dp |
| 相邻圆盘间隙 | 10dp |
| 内环半径下限 | 108dp（轮盘要浮在触发区上方，不能贴着手指） |
| 扇形张角 | 84°（两侧各留 3°，90° 会让最外格贴住屏幕边缘） |
| ≤3 个 | 单弧排布 |
| 4～6 个 | 两环 |
| 7～13 个 | 三环，13 个时正好 3 + 4 + 6 |

排布算法是「先定每环几个，再反推半径」：

1. 枚举所有「内少外多」的格数组合（13 个的三环共十来种，如 3+4+6、4+4+5、2+5+6）；
2. 对每种组合算出满足「同环相邻格弦长 ≥ 圆盘 + 间隙」所需的最小内环半径；
3. 取最紧凑的那一种 —— 13 个自然收敛到 **3+4+6**，正是 GestureEVO 的三层观感；
4. 外缘超出屏幕可用范围时先缩圆盘，缩到下限仍放不下才等比缩放。

第 1 步刻意不用「容量整除」的贪心：贪心在 4 个时会给出一环 1 个、二环 3 个的细长布局，
内环孤零零一个圆看起来像画错了。因此数量 ≥ 环数×2 时强制每环至少 2 个。

同一环内均分可用张角；不同环格数不同，因此**不做跨环角度对齐**（这也是它看起来像
「沿弧线铺开的三层网格」而不是方格子的原因）。

## 触发区域

| 项 | 存储键 | 取值 |
|---|---|---|
| 横向位置（中心） | `radial_trigger_area_x` | 0～100，默认 9 |
| 纵向位置（中心） | `radial_trigger_area_y` | 0～100，默认 95 |
| 宽度 | `radial_trigger_area_width` | 6～60，默认 20 |
| 高度 | `radial_trigger_area_height` | 4～40，默认 12 |
| 镜像到另一侧 | `radial_trigger_area_mirror` | 默认 true |

全部写在 `app_settings` 里，由 `RadialTriggerSettings` 统一读写，
跨进程通过 `NotificationSettingsProvider.get_radial_trigger` 暴露给 Launcher。

用百分比而不是 dp：触发区是「手指够得着的位置」，跟屏幕尺寸强相关、跟密度无关；
百分比在 6.1" 和 6.8" 上落在同一个相对位置，dp 会让小屏上的区偏大。

几何是纯函数（`Config.areas()` / `Config.hit()`）：

- `areas()` 算出主区，镜像时再算一块以屏幕中轴线对称的区（两块重合时只留一块）；
- 主区中心在屏幕左半边 → 依附 `BOTTOM_LEFT`，在右半边 → `BOTTOM_RIGHT`；
- 圆心内缩量 = 主区中心到「所依附那条竖直边」的距离，以及到屏幕底边的距离 ——
  `RadialLayoutSpec.fit` 用这两个值算「向上/向内的可用空间」，所以触发区越靠内、
  越靠上，轮盘会自动缩小，不会顶出屏幕。

早期版本是「底角热区（固定 22% 屏宽 × 上下各 40dp）+ 高度百分比」，
问题有两个：水平范围写死，用户没法把触发位置挪到顺手的地方；而且完全看不见自己设了什么。

## 数据来源

按顺序取，前一级为空才降级：

1. 「轮盘应用」名单（`RadialAppsStore`，SharedPreferences 里一条有序字符串，格式 `pkg|userId`）；
2. 「侧边栏快捷应用」名单（Room `FreeFormAppsEntity`，历史配置，兼容保留）；
3. 最近任务（`ActivityManager.getRecentTasks`，去重后取前 6 个）。

第 3 级只是兜底：绝大多数用户从没打开过任何一列表，纯配置制在真机上的体验就是
「从底角上滑毫无反应」。兜底刻意只取 6 个（两环）而不是填满 13 个三层 ——
一屏陌生应用摊在手指前面反而更难选。

## 分层

| 层 | 文件 | 职责 | 可单测 |
|---|---|---|---|
| 几何 | `xposed/ui/radial/RadialGeometry.kt` | 扇形布局、最近格命中、迟滞 | ✅ |
| 动画 | `xposed/ui/radial/RadialMotion.kt` | 入场/退场/选中/错峰曲线（纯函数） | ✅ |
| 尺寸 | `xposed/ui/radial/RadialLayoutSpec.kt` | 弧长间距、舒适半径、圆盘尺寸、1~3 环分配 | ✅ |
| 状态 | `xposed/ui/radial/RadialGestureGate.kt` | 触发区命中 → 展开 → 提交的状态机 | ✅ |
| 绘制 | `xposed/ui/radial/RadialMenuView.kt` | 单 Canvas 绘制（白色圆盘 + 圆形图标 + 选中环） | — |
| 装配 | `xposed/ui/radial/RadialMenuController.kt` | 取名单/图标、开悬浮窗、推指针、交出选中项 | — |
| 接入 | `xposed/hook/HookSwipeGesture.kt` | 复用 Launcher 输入流驱动轮盘 | — |
| 数据 | `providers/FreeformAppsProvider.kt` | 把两份名单只读暴露给 Launcher 进程 | — |
| 数据 | `providers/RadialAppsStore.kt` | 轮盘名单的读写（App 进程内） | — |
| 数据 | `providers/RadialTriggerSettings.kt` | 触发区域（中心/尺寸/镜像）的读写与命中判定 | ✅ |
| 输入 | `xposed/hook/utils/InputPilfer.kt` | 找到并调用 `pilferPointers()`，独占输入流 | — |
| 预览 | `ui/radial_area/*` | 触发区编辑页：滑杆 + 页内预览 + 真实屏幕浮层 | — |

## 悬浮窗

轮盘**开自己的悬浮窗**，而不是挂到 Launcher 的视图树上：Launcher 的视图只在
桌面/最近任务位于前台时可见，用户从第三方应用里上滑时轮盘会被应用窗口挡在后面
（表现为「有震动、小窗也能开，但什么也看不见」）。

窗口类型按 `TYPE_SYSTEM_OVERLAY` → `TYPE_APPLICATION_OVERLAY` 的顺序尝试，
都失败才退回 Launcher 的 DecorView。参数固定为
`FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`，
坐标仍由输入 Hook 推进来，窗口本身不参与触摸分发。


## 与两个参考实现的关系

| 维度 | FlymeFreeform | GestureEVO | 本项目 |
|---|---|---|---|
| 几何 | 极坐标 + 最近格心 | 圆环 + 每格矩形包含 | 极坐标 + 最近格心（取前者） |
| 命中缺陷 | 无（但 `keepRadius=0`，无迟滞） | 圆形图标配方形判定：四角假命中、格间丢选 | 最近格心 + 可测迟滞 |
| 绘制 | 单 Canvas | 每格一个 View + 属性动画 | 单 Canvas（取前者） |
| 动画 | 多段手写贝塞尔，可单测 | `AnimatorSet` + Overshoot 交错，参数写死 | 进度曲线纯函数，可单测 |
| 容量 | 固定 84°、≤7 格 | 网格列 × 行 | 84°、≤13 格（单弧 / 双环 / 三环 3+4+6） |
| 可测性 | 11 个纯函数单测 | 2 个几何单测 | 4 个测试类覆盖几何/动画/尺寸/状态机 |

刻意**没有**沿用 GestureEVO 的两处做法：

1. **不用「每格一个 View」**。轮盘每次展开都会 measure/layout 一遍，条目多时
   帧预算会被 View 体系吃掉，而且动画要写回 View 的中间态、退场无法从当前帧接管。
2. **不用矩形包含做命中**。图标是圆的，命中框是方的，四角会出现假命中、
   格间缝隙会丢选；最近格心天然无缝。

也**没有**照搬 FlymeFreeform 的硬约束：它的条目数、角度、半径都写死在
ColorOS 特定形态上，这里把半径做成按「弧长间距 + 舒适半径」自适应，
条目数只改变角度（5 个以内），增删条目不跳动。

## 测试

```bash
./gradlew :app:testDebugUnitTest --tests "io.relimus.zflow.xposed.ui.radial.*"
```

覆盖点：

- `RadialGeometryTest`：左右角镜像、槽位顺序、均匀间距、最近格命中、迟滞、扇区角度；
- `RadialMotionTest`：端点精确、展开/收拢单调、过冲有界、错峰顺序；
- `RadialLayoutSpecTest`：弧长间距不小于图标直径、13 个收敛到 3+4+6、4~6 个用两环且内环不孤零零、
  扇形不出屏、小屏等比缩放、触发区上移让圆心跟着上移、居中时轮盘自动缩小、条目数夹取；
- `RadialTriggerSettingsTest`：默认区覆盖两个底角、关闭镜像后另一侧不响应、
  圆心跟着区域中心走、加宽真的扩大命中范围、区域被夹在屏幕内、越界参数被修正；
- `RadialGestureGateTest`：位移不足不展开、横扫不展开、向下拖不展开、贴边竖直上滑可展开、
  一次手势只提交一次。

## 已知限制

- 轮盘条目上限 13：再往上加就只能缩圆盘，白色按钮会小到看不清图标。
- 三层时外缘可到约 308dp，接近单手拇指的舒适极限；只想用几个应用时建议保持 ≤6 个。
- **悬浮窗会被部分 ROM 压到 80% 不透明度**（小米/澎湃实测：`dumpsys window` 里
  `mAttrs.alpha=0.8`，即使客户端 `LayoutParams.alpha` 显式写 1.0 也会被改写）。
  原因是桌面进程没有 `SYSTEM_ALERT_WINDOW` 这个 appop，系统对「不可信悬浮窗」
  统一降透明度。表现是白色圆盘在浅色界面上略微透出背景。
  彻底解决要把绘制搬到 Z-Flow 自己的无障碍浮层（GestureEVO 走的就是那条路），
  当前版本先用「纯白圆盘 + 1.2dp 描边 + 更实的投影」把边界做清楚。
- 轮盘展开后桌面窗口会收到 `ACTION_CANCEL`（输入被独占），因此**展开期间**对桌面的
  长按拖动、图标拖拽都不可用；这是刻意取舍 —— 否则工作区会跟着手指翻页。
- 只按当前用户解析图标（名单里其它用户的应用图标可能不精确）。
- 图标在**后台线程**异步加载（最多 13 个 drawable），先出白圆盘、图标随后落位；
  位图按 `包名|userId|尺寸` 缓存 24 个，避免每次展开都重新解码。
- 依赖 Launcher 的 `onInputEvent` 能拿到 `ACTION_MOVE`——与原落点提示走的是同一条链路，
  因此能力边界与原手势一致，不会额外要求新权限。

## 排查：上滑没反应时看哪里

轮盘的每条失败路径都会**静默退回原本手势**，所以它自带一条常开的诊断链路
（不受 `HookSwipeGesture.ENABLE_DIAGNOSTIC_LOGS` 控制，见 `RadialTrace`）：

```bash
adb logcat -s ZFlowRadial
```

LSPosed 日志里前缀同样是 `ZFlowRadial`，没有 adb 也能看。

一次手势的正常链路是这几行：

```
[radial] down raw=(1380,3100) screen=1440x3200 area=(1152,2848)-(1440,3200) mirror=true enabled=true corner=BOTTOM_RIGHT thread=main ctx=...
[radial] armed corner=BOTTOM_RIGHT xy=(1380,3100) screen=1440x3200 slop=60 origin=(115,102) parent=DecorView
[radial] reveal try xy=(1350,3010) down=(1380,3100) handler=...LauncherSwipeHandlerV2@...
[radial] pilfer=ok
[radial] overlay attached type=2006 alpha=1.0
[radial] reveal ok corner=BOTTOM_RIGHT count=13 rings=3 shares=[3, 4, 6] screen=1440x3200 radius=598 icon=210 origin=(1325,3098) host=overlay(2006)
[radial] commit: pkg=com.tencent.mm user=0
```

`origin=` 是圆心（触发区中心），与 `armed` 行里的 `origin=` 内缩量一致：
`圆心 x = 1440 - 115`、`圆心 y = 3200 - 102`。

对照缺席的那一行：

| 缺哪行 | 含义 | 处理 |
|---|---|---|
| `armed` 之前是 `skip: switch off` | 设置里的「上滑轮盘」没开 | 打开开关；若确认已开，看是否 `skip: no context` |
| `corner=null` | 按下点不在触发区里（`area=` 给出当前区域的实际像素范围） | 到「触发区域」把方块挪/放大到手指真正落下的位置，编辑页有实时预览 |
| `down` 之后没有 `armed` | 命中了但开关没开（会先打 `skip: switch off`） | 打开「上滑轮盘」开关 |
| 只有 `armed`，没有 `reveal try` | `ACTION_MOVE` 没送到输入 Hook，或方向被 `shouldReveal` 挡掉 | 松手时会打 `up: armed but never revealed`；若连松手日志都没有，说明该 ROM 截断了 MOVE 流 |
| `overlay type=2006 failed` 后仍 `overlay attached` | 系统浮层被拒，退回了 `APPLICATION_OVERLAY` | 属于预期降级；若两种都失败会继续打 `overlay type=... failed` |
| `reveal abort: empty_favorites` | 三份名单都空（同时会弹一次 Toast） | 到「轮盘应用」里勾几个应用 |
| `reveal abort: provider_denied` | Launcher UID 未被 Provider 放行 | 看 `adb logcat -s FreeformAppsProvider` 里的 uid，补进白名单 |
| `reveal abort: provider_error` | Provider 未注册 / Binder 异常 | 确认 APK 已更新（该 Provider 是新增的） |
| `reveal ok` 但屏幕没东西 | 视图挂上了但没画出来 | 看 `host=` 是 overlay 还是 viewgroup，以及该窗口的 `alpha`（可能被系统压到 0.8） |
| `fallback: recent tasks count=0` | 没有名单也没有最近任务 | 属于首次开机未使用状态，先用几个应用再试 |
| `abandon: direction rejected` | 底角起手但方向不是上滑（横滑返回 / 下拖） | **正常行为**，已把事件交还 Launcher |
| `down: reap stale gesture` | 上一轮手势没正常收尾，新 DOWN 时清场 | **正常兜底**，若频繁出现说明 UP 一直没送达 |
| `move: menu showing without gate` | 悬浮窗残留且已无活跃手势 | **正常兜底**，同上 |
| `watchdog: gesture never finished` | 超时（未展开 2s / 已展开 6s）没有新事件 | **正常兜底**，清场但不提交选择 |
| `pilfer=unavailable` | 反射没找到 `pilferPointers()`（ROM 内部结构变了），只靠 `dispatchTouchEvent` 兜底 | 功能仍可用；若此时桌面仍在跟手滑，说明兜底层也没装上（启动日志里有 `launcher touch dispatch hook installed=false`） |
| `reveal slow: Nms` | reveal 在主线程上耗时超过一帧 | N 接近或超过 1000ms 要警惕：那时 `contentResolver.call` 很可能正在冷启动 Z-Flow 进程 |

### 出现「进程 system 没有响应」

这是 **ANR 记错对象**的典型样子：真正被卡的是 Launcher 的输入消费，但
`recents_animation_input_consumer` 注册在 system 侧，所以 ANR 打到了 `system` 进程上。

```bash
adb logcat -b events -d | grep am_anr
# am_anr: [0,<pid>,system,...,Input dispatching timed out
#         (recents_animation_input_consumer is not responding. Waited 5001ms for MotionEvent).]
```

看到这条就先查 `adb logcat -s ZFlowRadial` 里有没有上面表格中的四个兜底日志。
四条出口都正常时不会再出现该 ANR；若仍出现，按顺序怀疑：

1. `reveal slow` 动辄上千毫秒 —— reveal 在主线程上做跨进程调用，事件堆积；
2. 兜底日志一条都没有 —— 该 ROM 上 `ACTION_UP` 与看门狗都被绕过，需要重新确认 hook 点；
3. 有 `armed` 但之后长时间空白 —— 主线程被别的东西卡住，看 `dumpsys` 里的主线程栈。

