# 底角上滑轮盘

从屏幕左右下角斜向上滑，扇形展开最多 6 个应用，滑到目标松手即以小窗打开。

## 交互规则

| 阶段 | 判定 | 说明 |
|---|---|---|
| 起手 | 按下点落在左下/右下热区内（56dp 高、22% 屏宽） | 超出热区一律按原有上滑处理 |
| 展开 | 位移 > `touchSlop`，`dy < 0`，且指针落在扇形张开的一侧（容差 20°） | 只认「斜向内上滑」，沿底边横扫不会触发 |
| 选择 | 最近格心 + 进入半径，带 0.62×圆盘直径的迟滞 | 手指滑出弧线外仍可继续选，边界不抖 |
| 提交 | 松手时取选中项 | 未选中任何一格则不打开，退回 Launcher 原本行为 |

- 开关：设置 → 启动与触发 → 桌面与手势 → 上滑轮盘（默认开启开关项见 `settings.xml`）。
- 名单：设置 → 启动与触发 → 桌面与手势 → 轮盘应用（最多 6 个，**勾选顺序即轮盘排列顺序**）。
- 展开期间不再显示原有的落点提示：两套浮层同时出现会让用户不知道松手会发生什么。
- 展开期间 Launcher 的输入被**整段吃掉**（DOWN/MOVE/UP/CANCEL 都拦），
  否则最近任务卡片会和轮盘一起出现。
- Android 17 上 `ACTION_UP` 不一定送达输入 Hook，因此手势结束回调里会再补一次提交，
  由 `RadialGestureGate.finish()` 保证一次手势只提交一次。

## 尺寸与排布

视觉基线对齐 GestureEVO 的圆形按钮：圆盘大、间隙均匀。

| 参数 | 值 |
|---|---|
| 圆盘（白色圆底）直径 | 56dp，最小不低于 34dp |
| 相邻圆盘间隙 | 10dp |
| 外缘上限（半径 + 半个圆盘 + 内边距） | 250dp |
| 扇形张角 | 84°（两侧各留 3°，90° 会让最外格贴住屏幕边缘） |
| ≤5 个 | 单弧排布 |
| 6 个 | 内外两环、**角度对齐**（沿弧线弯过去的 2 列 × 3 行网格） |

半径不是写死的：先按「同环相邻格子的弦长 ≥ 圆盘 + 间隙」求出内环最小半径，
再用「外缘 ≤ 250dp」把圆盘往下收；两者冲突时优先保证圆盘饱满度不低于 34dp。

两环**刻意不做半格错排**：错排后内环与外环的圆心距会掉到接近一个圆盘直径，
真机上看起来是糊在一起的一团（这也是最初版本被反馈「太乱」的原因）。

## 数据来源

按顺序取，前一级为空才降级：

1. 「轮盘应用」名单（`RadialAppsStore`，SharedPreferences 里一条有序字符串，格式 `pkg|userId`）；
2. 「侧边栏快捷应用」名单（Room `FreeFormAppsEntity`，历史配置，兼容保留）；
3. 最近任务（`ActivityManager.getRecentTasks`，去重后取前 6 个）。

第 3 级只是兜底：绝大多数用户从没打开过任何一列表，纯配置制在真机上的体验就是
「从底角上滑毫无反应」。

## 分层

| 层 | 文件 | 职责 | 可单测 |
|---|---|---|---|
| 几何 | `xposed/ui/radial/RadialGeometry.kt` | 扇形布局、最近格命中、迟滞 | ✅ |
| 动画 | `xposed/ui/radial/RadialMotion.kt` | 入场/退场/选中/错峰曲线（纯函数） | ✅ |
| 尺寸 | `xposed/ui/radial/RadialLayoutSpec.kt` | 弧长间距、舒适半径、圆盘尺寸、单/双环 | ✅ |
| 状态 | `xposed/ui/radial/RadialGestureGate.kt` | 热区 → 展开 → 提交的状态机 | ✅ |
| 绘制 | `xposed/ui/radial/RadialMenuView.kt` | 单 Canvas 绘制（白色圆盘 + 圆形图标 + 选中环） | — |
| 装配 | `xposed/ui/radial/RadialMenuController.kt` | 取名单/图标、开悬浮窗、推指针、交出选中项 | — |
| 接入 | `xposed/hook/HookSwipeGesture.kt` | 复用 Launcher 输入流驱动轮盘 | — |
| 数据 | `providers/FreeformAppsProvider.kt` | 把两份名单只读暴露给 Launcher 进程 | — |
| 数据 | `providers/RadialAppsStore.kt` | 轮盘名单的读写（App 进程内） | — |

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
| 容量 | 固定 84°、≤7 格 | 网格列 × 行 | 84°、≤6 格（单弧 / 2×3） |
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
- `RadialLayoutSpecTest`：弧长间距不小于图标直径、扇形不出屏、小屏等比缩放、条目数夹取；
- `RadialGestureGateTest`：热区命中、位移不足不展开、横扫不展开、一次手势只提交一次。

## 已知限制

- 轮盘条目上限 6：84° 弧上再多就只能靠缩圆盘，观感会明显变差。
- **悬浮窗会被部分 ROM 压到 80% 不透明度**（小米/澎湃实测：`dumpsys window` 里
  `mAttrs.alpha=0.8`，即使客户端 `LayoutParams.alpha` 显式写 1.0 也会被改写）。
  原因是桌面进程没有 `SYSTEM_ALERT_WINDOW` 这个 appop，系统对「不可信悬浮窗」
  统一降透明度。表现是白色圆盘在浅色界面上略微透出背景。
  彻底解决要把绘制搬到 Z-Flow 自己的无障碍浮层（GestureEVO 走的就是那条路），
  当前版本先用「纯白圆盘 + 1.2dp 描边 + 更实的投影」把边界做清楚。
- 只按当前用户解析图标（名单里其它用户的应用图标可能不精确）。
- 图标在首次展开时同步加载（≤6 个 drawable），之后命中进程内缓存。
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
[radial] down raw=(1400,3160) screen=1440x3200 hotspot=360x210 enabled=true corner=BOTTOM_RIGHT ctx=com.android.quickstep.TouchInteractionHandler
[radial] armed corner=BOTTOM_RIGHT xy=(1400,3160) screen=1440x3200 slop=60 parent=DecorView
[radial] reveal try xy=(1390,3100) down=(1400,3160) handler=...LauncherSwipeHandlerV2@...
[radial] overlay attached type=2006 alpha=1.0
[radial] reveal ok corner=BOTTOM_RIGHT count=3 radius=550 icon=210 origin=(1410,3170) host=overlay(2006)
[radial] commit: pkg=com.tencent.mm user=0
```

对照缺席的那一行：

| 缺哪行 | 含义 | 处理 |
|---|---|---|
| `armed` 之前是 `skip: switch off` | 设置里的「上滑轮盘」没开 | 打开开关；若确认已开，看是否 `skip: no context` |
| `skip: outside corner hotspot` | 起手点不在底角热区（56dp 高 / 22% 屏宽） | 从更靠角落的位置起手，或调 `RADIAL_CORNER_HOTSPOT_DP` / `RADIAL_CORNER_WIDTH_FRACTION` |
| 只有 `armed`，没有 `reveal try` | `ACTION_MOVE` 没送到输入 Hook，或方向被 `shouldReveal` 挡掉 | 松手时会打 `up: armed but never revealed`；若连松手日志都没有，说明该 ROM 截断了 MOVE 流 |
| `overlay type=2006 failed` 后仍 `overlay attached` | 系统浮层被拒，退回了 `APPLICATION_OVERLAY` | 属于预期降级；若两种都失败会继续打 `overlay type=... failed` |
| `reveal abort: empty_favorites` | 三份名单都空（同时会弹一次 Toast） | 到「轮盘应用」里勾几个应用 |
| `reveal abort: provider_denied` | Launcher UID 未被 Provider 放行 | 看 `adb logcat -s FreeformAppsProvider` 里的 uid，补进白名单 |
| `reveal abort: provider_error` | Provider 未注册 / Binder 异常 | 确认 APK 已更新（该 Provider 是新增的） |
| `reveal ok` 但屏幕没东西 | 视图挂上了但没画出来 | 看 `host=` 是 overlay 还是 viewgroup，以及该窗口的 `alpha`（可能被系统压到 0.8） |
| `fallback: recent tasks count=0` | 没有名单也没有最近任务 | 属于首次开机未使用状态，先用几个应用再试 |
