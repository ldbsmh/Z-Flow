# 一次上滑开出两个小窗：成因与修复

## 现象

最近任务上滑（手势）一次，屏幕上出现两个小窗；长按、悬浮窗入口正常。

## 链路与三个漏洞

一次上滑只应该产出一个窗口。这条链路上有三个独立的地方会重复放行：

### 1. `INIT_DEDUPLICATION_MS = 32` 太短 → `beginGesture` 跑两次

`AbsSwipeUpHandler` / `LauncherSwipeHandlerV2` / `FallbackSwipeHandler` 三个类都装了
`initStateCallbacks` 的 hook，同一个 handler 实例可能被先后命中两次。去重窗口只有 32ms，
一旦两个回调跨过一帧，就会：

```
initializeHandlerFromInit(handler)      // 第一次
  -> beginGesture(handler)              // gestureGeneration = N
  -> runOnceAtState(STATE_END_TARGET_SET, runnableA)   // A 的 generation = N

initializeHandlerFromInit(handler)      // 32ms 后第二次
  -> beginGesture(handler)              // gestureGeneration = N+1
  -> runOnceAtState(STATE_END_TARGET_SET, runnableB)   // B 的 generation = N+1
```

`runnableA` 里那道 guard 是 `generation != gestureGeneration`，于是 A 判定自己过期；
但 `runOnceAtState` 是在**状态到达时**才执行的，实际顺序往往是 A 先跑完再 B 跑，
或者 A、B 同时命中各自“当时”的 generation —— 两代回调都会走到 `handleGestureEnd`，
各发一次广播。

### 2. `handleGestureEnd` 里提交标记的清理位置太晚

```kotlin
val commitByHint = hintCommittedThisGesture && dropTargetShowing   // 读
val shouldLaunch = commitByHint || releaseInDropTarget
lifecycleLog(...)          // 中间还有日志、removeHint
...
hintCommittedThisGesture = false   // 才清
```

第二次进入函数时，如果这中间没有任何东西把它清掉（`beginGesture` 恰好又把
`hintCommittedThisGesture` 置回 true，见第 1 条），它会读取到 true 再发一次。

### 3. 下游去重键带 taskId

`FreeformRequestHandler`：

```kotlin
val key = "$pkg|${component}|$userId|$taskId"
```

两次重复提交只隔几十毫秒，但 `getRunningTask(handler)` 在这期间读到的 taskId 可能已经
从 X 变成 X+1（最近任务正在切栈），键不同 → `inFlight` 拦不住 → 两个窗口。
同理 `pendingMiniLaunch` 只 `removeCallbacks`，挡不住**已经发出**的广播。

## 修复

| 位置 | 改动 |
| --- | --- |
| `HookSwipeGesture.INIT_DEDUPLICATION_MS` | 32ms → 600ms。一个手势的完整生命周期远短于 600ms，只会合并同一次初始化 |
| `HookSwipeGesture.handleGestureEnd` | 提交标记**先读后立即清零**（消费式），第二次进入读不到 true |
| `HookSwipeGesture.scheduleMiniLaunch` | 新增与 taskId 无关的 `MINI_LAUNCH_DEBOUNCE_MS = 1000ms` 时间闸；命中时同时清掉待发回调 |
| `FreeformRequestHandler` | 去重键去掉 taskId；新增 `recentRequests` 时间窗口（默认 1.2s）+ 定期裁剪 |

三层是冗余的：任何一层生效都不会出现两个窗口，日志里能看到是被哪一层拦下的
（`duplicate mini launch suppressed` / `duplicate request ignored`）。

## 为什么不用“只留第一层”的思路

- 只改去重窗口治标：用户真的在 600ms 内连做两次上滑时会被吞掉一次。
- 只改下游：hook 侧仍然为一次手势发两次跨进程广播，浪费且日志噪音大。
- 只改消费式读取：`beginGesture` 重入时会把标记重新置 true，挡不住。

因此 hook 侧负责“一次手势只排一次队”，App 侧负责“同一目标在短窗口内只创建一次”。
