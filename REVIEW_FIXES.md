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

### 6. 广播入口鉴权（P1）
之前动态注册用的是 `RECEIVER_EXPORTED` 且不带权限，任何应用都能伪造
`packageName / userId / taskId` 触发小窗。

现在两条注册路径都带 **`android.permission.DUMP`**（`protectionLevel="signature|privileged"`，
只有 system_server、root、adb shell 能持有）：

- 动态：`StartFreeformReceiver.register()`，分 SDK 分支，注册失败返回 null 不抛异常。
- 静态：新增 `broadcast/FreeformRequestReceiver`，Manifest 中
  `android:exported="true" android:permission="android.permission.DUMP"`。

**为什么选 DUMP**：小窗请求的真实来源是 system_server 进程（Xposed hook 在系统进程里，
hook 的是状态栏/任务卡，广播由系统进程发出）。DUMP 对它天然可用，普通三方应用拿不到。
发送侧 `HookLauncher` 先 `checkCallingOrSelfPermission(DUMP)` 自检，未授权时记日志并放弃。

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
- `isBlacklisted()` 同样返回 `Boolean?`。**失败策略是 fail-closed**：
  确认不了就当作在黑名单里，宁可不创建小窗。理由是被屏蔽的应用不该因为一次数据库抖动被放行。

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
