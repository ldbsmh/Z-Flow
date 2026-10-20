# Z-Flow 代码审查修复说明

本文件记录三轮审查意见对应的实际改动，**以源码为准**，文档与实现不一致处以代码为准。

> 构建验证的限制：开发环境为 aarch64 Linux，Android SDK 自带的 `aidl` 是 x86_64 ELF 无法执行。
> 本地只做了 `:app:compileDebugKotlin`（含 KSP + Room）的类型检查，AIDL 用临时生成的占位源码替代，
> AIDL 文件与构建脚本本身未改动。**这不等于真实 AIDL 的完整构建，更不能证明设备运行时行为正确**，
> 运行时行为以 CI 产物 + 真机验证为准。

---

## 第一轮：架构性问题

### 1. 主线程查库 → 全链路协程化
- `MyDatabase` 移除 `allowMainThreadQueries()`。
- `DatabaseRepository` 写操作全部改为 `suspend` + `withContext(ioDispatcher)`；批量写入走
  `database.withTransaction { }`，返回 `WriteReport(error)`（**注意：只有 `error` 一个字段**，
  `isSuccess` 是派生属性；没有 `affected` 字段）。
- Provider 的 Binder 线程需要同步查询，拆到独立 `NotificationSyncDao`，避免事务内起协程抛异常。

### 2. 页面生命周期
- `ChooseAppsActivity` 由 ViewModel 持有状态，页面按生命周期消费，不再在 `Dispatchers.IO` 里直接摸 Adapter。
- `SplashActivity` 的权限检查放到 `Dispatchers.IO`，`startForegroundService` 加 `SecurityException` 保护。

### 3. 排序功能实际失效
`sortNum` 是自增主键，逐条 `@Update` 交换顺序会撞主键、异常被吞（表现为“拖完弹回”）。
`updateFreeFormSort()` 在单个事务内先把旧主键整体挪到负数区，再按新顺序写回 `0..n-1`。

### 4. 通知名单主键冲突
`NotificationAppsEntity` 主键改为 `(packageName, userId)`，插入用 `OnConflictStrategy.IGNORE`，
新增 **MIGRATION_6_7**（重建表 + `INSERT OR IGNORE`，旧数据不丢行）。

---

## 第二轮：修复引出的功能性回归

### 5. 小窗从所有入口都启动不了（严重回归）
上一轮为消除 exported Service 风险，把 `FreeformService.onStartCommand` 改成丢弃
`ACTION_START_INTENT`，并删掉了 Manifest 声明。但三条创建链路全都依赖它，于是全部静默失效。

现在的结构：

- **`service/FreeformRequestHandler.kt`** 是小窗创建的**唯一出口**，直接调用 `FreeformManagerProxy`，
  集中做组件解析、连接状态、黑名单校验、参数默认值。
- `FreeformService` 恢复完整逻辑，Manifest 以 `exported="false"` 重新声明（本应用进程内使用）。
- `AllAppsAdapter` / `ChooseAppFloatingAdapter` 直接调用处理器。
- `HookNotificationAction` 不再 `startService`（它运行在第三方进程，够不到非导出组件），改发广播。
- 失败时弹 `freeform_start_failed`，不再无声无息。

---

## 第三轮：安全边界、可用性与数据正确性

### 6. 广播入口鉴权（P1 → 已回退权限方案）
之前动态注册用的是 `RECEIVER_EXPORTED` 且不带权限，任何应用都能伪造
`packageName / userId / taskId` 触发小窗。

**第一版做法（已废弃）**：给动态和静态接收器都加 `android.permission.DUMP`。
**这一版直接导致所有入口失效**，必须记录清楚原因，避免以后有人再改回去：

广播的三个真实发送方都是**被 Hook 的宿主进程**——纯血 Launcher（长按 SystemShortcut）、
SystemUI（最近任务上滑）、任意第三方应用进程（通知按钮）。它们都不持有 DUMP。
关键点在于：权限不足时 `sendBroadcast()` 在 JVM 层**不会抛异常**，取消发生在
`AMS.broadcastIntentLocked`，只留一条 Permission Denial 日志，App 侧完全收不到。
用户看到的现象就是：
- 手势上滑没有小窗；
- 桌面长按“打开小窗”菜单什么都不做，直接掉回系统默认行为（本机表现为跳应用商店）。

**现在的做法**（`broadcast/StartFreeformReceiver.kt`）：
- 接收器 `exported` + `RECEIVER_EXPORTED`，不加任何系统权限；
- 所有发送方统一用 `StartFreeformReceiver.newIntent()` 构造广播，必须带内部标记
  `EXTRA_SENDER = "io.relimus.zflow"`；
- 接收端 `dispatch()` 校验该标记，缺失即丢弃；
- 真正的窗口管理仍在 system_server 侧的 `IFreeformManager`，伪造的最坏结果只是“把某个应用
  开成小窗”，不构成越权执行。
- `HookLauncher` 的 DUMP 自检（会让长按入口直接 return）一并删除。

### 7. 两种保活模式都能收到请求（P1）
- `KeepAliveService.onServiceConnected()` 注册（已有）。
- `ForegroundService.onCreate()` 补上同样的注册 —— 之前只注册在无障碍保活服务里，
  前台服务模式下没有接收者。
- 再加**静态接收器**兜底：App 进程被杀、两个服务都不在跑时，由系统拉起进程处理。

### 8. 通知名单的多用户键（P1）
`getAllNotificationPackageNames()` 只查 `DISTINCT packageName`，随后统一拼 `|0`，
把工作资料（userId=10 等）的勾选错误归到主用户，与 UI 的 `packageName|userId` 勾选键冲突。

现在 `getAllNotificationKeys()` 基于 `getAllNotificationEntries()`（带 `userId`）生成真实键；
`NotificationSettingsProvider` 的 cursor 同时返回 `packageName` + `userId` 两列。

### 9. 数据库失败的语义（P2）
- 同步查询失败返回 **null**，而不是 `emptyList()` —— 区分“没配置”和“读不出来”。
- `getAllNotificationPackageNames()` / `getAllNotificationEntries()` 均按此约定，
  Provider 收到 null 返回 `denied`，SystemUI 不会把数据库故障当成“没有任何应用启用接管”。
- `isBlacklisted()` 同样返回 `Boolean?`。**App 进程内的入口 fail-closed**：
  确认不了就当作在黑名单里，宁可不创建小窗。
- **但 hook 侧（Launcher / SystemUI 进程）必须 fail-open**：`BlacklistProvider` 拒绝时返回的是
  带 `EXTRA_DENIED` 的空 Bundle，`getBoolean(RESULT, false)` 取到的是默认 false，
  “拒绝”会被当成“放行”；而在 hook 侧把 Provider 不可用当黑名单处理，会让用户在
  Z-Flow 进程被杀/升级期间周期性遇到“手势毫无反应”，且没有提示。
  hook 只做尽量过滤，最终裁决在 App 进程（那里查 Room，失败才拒绝）。

### 10. 手势热路径的 Binder（P2）
之前每次判断都要先 `contentResolver.call` 查版本号，缓存只省掉第二次调用。

现在的策略：
- 缓存命中后 **`CACHE_REVALIDATE_MS`（3s）内完全不发 Binder**，直接返回本地值。
- 窗口过期才查一次版本号；版本变了再拉具体名单。
- `invalidateListCaches()` 供同进程内主动失效（设置页写入后立即生效）。
- 条目上限 `CACHE_MAX_ENTRIES`（512），超出整体清空，避免被 hook 的长生命周期进程无限增长。
- 代价：跨进程写入最多有 3s 可见性延迟。这是热路径 IPC 与实时性的取舍，可通过常量调整。

### 11. 横屏全选真正批量写入（P2）
原来 `insertAllApps()` 是 `clear_all` + 逐个 `add`，每个应用一次 IPC、一次 SharedPreferences 写入、
一次版本号自增，中途异常还会留下半份名单。

新增 `LandscapeAppsProvider.METHOD_REPLACE_ALL`：一次调用传入完整名单，单次 `commit`，
版本号只加 1。语义明确为**整体替换**（与全选/反选一致），不再和旧的逐个追加混用。

### 12. 写入结果被真正使用（P2）
`ChooseAppsViewModel` 之前不管 `WriteReport.isSuccess` 都推进版本号，造成无谓的缓存失效。
现在只在写入成功时 `notifyNotificationAppsChanged()`，失败记录 `WriteReport.error`。

### 13. 悬浮视图协程泄漏（P2）
`ChooseAppFloatingView` 的 `MainScope()` 从未取消。新增 `release()`：取消作用域、
移除窗口、清空 View 引用，可重复调用。`FloatingWindowController.detach()` 调用它，
`attach()` 时重建视图，避免复用已释放的实例。

### 14. 顺带修掉的问题
- `ProviderAccess.isDenied()` 之前被 `RemoteSettings` 引用但实际不存在（靠局部扩展函数绕开），
  现在补成统一的顶层函数。
- `HookNotificationAction` 发送广播时补传 `sbn.userId`，工作资料的通知不再被当成主用户打开。
- `NotificationSettingsProvider.query()` 的 `MatrixCursor.addRow` 显式标注类型，消除
  “reified 类型推断为交集类型”的编译错误。

---

## 明确未做 / 仍需验证

- `FreeformWindow` / `FreeformManager` 的窗口创建销毁状态机（`Attached/Removing/Detached`）未重构。
  旋转期间快速重复移除/添加、`addView()` 失败后的状态恢复，仍需真机复现验证。
- Provider 仍为 `exported=true`（launcher / SystemUI 需要读），安全边界是代码层 UID 鉴权 + 只读返回，
  没有改成自定义 signature 权限（被 hook 的三方进程无法被授予）。
- 没有补充自动化测试。多用户、权限拒绝、迁移、请求队列上限这些场景目前只有人工验证路径。
- 小窗请求队列用 `ThreadPoolExecutor` + 有界 `LinkedBlockingQueue(MAX_PENDING)`，
  满载时 `AbortPolicy` 丢弃并记日志；同一 `packageName|userId|taskId` 在处理期间去重。
  但没有做基于时间窗口的限流。
