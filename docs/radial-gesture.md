# 底角上滑轮盘

从屏幕左右下角斜向上滑，扇形展开最多 6 个收藏应用，滑到目标松手即以小窗打开。

## 交互规则

| 阶段 | 判定 | 说明 |
|---|---|---|
| 起手 | 按下点落在左下/右下 32dp 热区内 | 超出热区一律按原有上滑处理 |
| 展开 | 位移 > 2 × `touchSlop`，且指针落在扇形张开的一侧 | 只认「斜向内上滑」，沿底边横扫不会触发 |
| 选择 | 最近格心 + 进入半径，带 0.62×图标直径的迟滞 | 手指滑出弧线外仍可继续选，边界不抖 |
| 提交 | 松手时取选中项 | 未选中任何一格则不打开，退回 Launcher 原本行为 |

- 开关：设置 → 启动与触发 → 桌面与手势 → 上滑轮盘（默认关闭）。
- 展开期间不再显示原有的落点提示：两套浮层同时出现会让用户不知道松手会发生什么。
- Android 17 上 `ACTION_UP` 不一定送达输入 Hook，因此手势结束回调里会再补一次提交，
  由 `RadialGestureGate.finish()` 保证一次手势只提交一次。

## 分层

| 层 | 文件 | 职责 | 可单测 |
|---|---|---|---|
| 几何 | `xposed/ui/radial/RadialGeometry.kt` | 扇形布局、最近格命中、迟滞 | ✅ |
| 动画 | `xposed/ui/radial/RadialMotion.kt` | 入场/退场/选中/错峰曲线（纯函数） | ✅ |
| 尺寸 | `xposed/ui/radial/RadialLayoutSpec.kt` | 弧长间距、屏幕内约束、图标尺寸 | ✅ |
| 状态 | `xposed/ui/radial/RadialGestureGate.kt` | 热区 → 展开 → 提交的状态机 | ✅ |
| 绘制 | `xposed/ui/radial/RadialMenuView.kt` | 单 Canvas 绘制（圆角图标、选中环、扇区） | — |
| 装配 | `xposed/ui/radial/RadialMenuController.kt` | 取名单/图标、挂视图、推指针、交出选中项 | — |
| 接入 | `xposed/hook/HookSwipeGesture.kt` | 复用 Launcher 输入流驱动轮盘 | — |
| 数据 | `providers/FreeformAppsProvider.kt` | 把收藏名单只读暴露给 Launcher 进程 | — |

## 与两个参考实现的关系

| 维度 | FlymeFreeform | GestureEVO | 本项目 |
|---|---|---|---|
| 几何 | 极坐标 + 最近格心 | 圆环 + 每格矩形包含 | 极坐标 + 最近格心（取前者） |
| 命中缺陷 | 无（但 `keepRadius=0`，无迟滞） | 圆形图标配方形判定：四角假命中、格间丢选 | 最近格心 + 可测迟滞 |
| 绘制 | 单 Canvas | 每格一个 View + 属性动画 | 单 Canvas（取前者） |
| 动画 | 多段手写贝塞尔，可单测 | `AnimatorSet` + Overshoot 交错，参数写死 | 进度曲线纯函数，可单测 |
| 容量 | 固定 84°、≤7 格 | 多环、任意象限 | 84°、≤6 格（够用且不挤） |
| 可测性 | 11 个纯函数单测 | 2 个几何单测 | 4 个测试类覆盖几何/动画/尺寸/状态机 |

刻意**没有**沿用 GestureEVO 的两处做法：

1. **不用「每格一个 View」**。轮盘每次展开都会 measure/layout 一遍，条目多时
   帧预算会被 View 体系吃掉，而且动画要写回 View 的中间态、退场无法从当前帧接管。
2. **不用矩形包含做命中**。图标是圆的，命中框是方的，四角会出现假命中、
   格间缝隙会丢选；最近格心天然无缝。

也**没有**照搬 FlymeFreeform 的硬约束：它的条目数、角度、半径都写死在
ColorOS 特定形态上，这里把半径做成按「弧长间距 + 屏幕内」自适应，
条目数只改变角度，增删条目不跳动。

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

- 轮盘条目上限 6：84° 弧上再多会互相压住，也不便于精确选择。
- 只按当前用户解析图标（收藏列表里其它用户的应用图标可能不精确）。
- 图标在首次展开时同步加载（≤6 个 drawable），之后命中进程内缓存。
- 依赖 Launcher 的 `onInputEvent` 能拿到 `ACTION_MOVE`——与原落点提示走的是同一条链路，
  因此能力边界与原手势一致，不会额外要求新权限。
