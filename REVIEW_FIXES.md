# Z-Flow 代码审查修复说明

本次改动基于项目审查意见，共修改 26 个文件、新增 2 个文件。所有改动均通过 `:app:compileDebugKotlin`
（含 KSP + Room 注解处理）验证：0 error。

> 说明：本机是 aarch64 环境，Android SDK 自带的 `aidl` 是 x86_64 ELF，无法执行。
> 类型检查时用临时生成的 AIDL 占位源码替代，AIDL 文件本身与构建脚本未做任何修改。

---

## P0：会丢数据 / 会崩溃 / 主线程阻塞

### 1. 主线程查库 → 全链路协程化
- `MyDatabase` 移除 `allowMainThreadQueries()`，并合并为一次 `addMigrations(...)` 调用。
- `DatabaseRepository` 全面重写：所有写操作改为 `suspend`，内部 `withContext(ioDispatcher)`；
  批量写入用 `database.withTransaction { }`；失败不再静默——返回 `WriteReport(affected, error)` 并记录日志。
- Room 禁止在事务中起协程，因此同步查询（ContentProvider 的 Binder 线程用）拆到独立 DAO
  `NotificationSyncDao`，避免“查询在事务里、切线程直接抛异常”。

### 2. 页面生命周期
- `ChooseAppsActivity`：`Dispatchers.IO` 中直接操作 Adapter/View 的问题改为 ViewModel 持有状态 +
  页面按生命周期消费；重进页面时不再因 `adapter != null` 直接 return 导致列表空白。
- `SplashActivity`：`lifecycleScope` 中在后台线程启动前台服务（会绑定 Activity）→ 移回主线程；
  `ForegroundService` 启动加异常保护。

### 3. 一枪崩
- `ChooseAppFloatingView` 的 `repository.isBlacklisted()` 在协程里逐应用查询（N 次跨线程查库）→
  改为一次性取回黑名单集合做 O(1) 匹配。
- `deleteNotInstall` 改为 `suspend`，调用方不再包一层 `Dispatchers.IO`。

### 4. 排序功能实际失效
- `sortNum` 是自增主键，逐条 `@Update` 交换顺序会撞主键、异常被吞，表现为“拖完弹回”。
  新增 `updateFreeFormSort()`：单个事务内先把旧主键整体挪到负数区，再按新顺序写回 0..n-1。
- `AppsSortRecyclerAdapter` 清理了无法编译的 `PackageManagerLike` 类型别名、修正 `ItemViewHolder`
  接收 `View` 的签名、`contextOrNull()` 的死代码。

### 5. 通知名单主键冲突
- `NotificationAppsEntity` 主键改为 `(packageName, userId)`，`INSERT` 改为 `OnConflictStrategy.IGNORE`。
  新增 **MIGRATION_6_7**（重建表 + `INSERT OR IGNORE` 迁移，旧数据不重复不会丢行），数据库版本 6 → 7。

---

## P1：安全性

### 6. Provider 写操作无鉴权
新增 `providers/ProviderAccess.kt`：统一判定调用方 UID（本应用 / system / root），
三个 ContentProvider 的 `insert / delete / update` 全部先过 `ProviderAccess.canWrite()`，
被拒绝时返回带 `denied` 标记的空 Bundle 而不是静默成功。Provider 在 Manifest 中补上
`tools:ignore="ExportedContentProvider"` 并写明“为什么必须 exported、安全边界在哪”。

### 7. 伪造的 exported Service
Manifest 里 `.xposed.services.FreeformService` 实际是 App 进程内的 `object`（不是 Android Service），
却被声明成 `exported=true` 并带 intent-filter，任何应用都能 `startService` 触发。
已删除该声明，同时移除 `FloatingWindowController` 里对其的 `startService/stopService`
（Xposed 场景下 system_server 不会走这条路，属无效代码）。
`ForegroundService` 补 `stopWithTask="false"` 与 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`。

### 8. 默认值收敛
`NotificationSettingsProvider` 读取失败/被拒时不再返回 `maxFreeform = 5` 之类“看起来正常但错误”的默认值，
改为返回空 Bundle，调用方可区分“没取到”和“取到了 0”。

---

## P2：性能与正确性

### 9. 手势热路径的 Binder 往返
`HookSwipeGesture` 每次缩放回调都会对横屏名单做一次 `contentResolver.call`。
现在给 Provider 加了**版本号**（`get_version`，写入时自增），hook 侧维护
`packageName -> Boolean` 缓存 + 版本比对：版本未变直接命中缓存，名单一改立即失效。
黑名单同理（`blacklistCache`）。失败时退回缓存值，不再一律 false。

### 10. 名单变更即时生效
- 设置页改通知名单：`bumpVersion(NotificationSettingsProvider.KEY_APP_LIST_VERSION)`
  替代原来只 `putBoolean` 一下 `notify_freeform_changed`（没人读）的写法。
- 黑名单写操作成功后同样推进版本号。
- `RemoteSettings` 读取通知名单前先比对版本，变化立即丢缓存，不再干等 250ms TTL；
  `fetchBlacklistVersion()` / `fetchVersion(..., authority)` 修正了原来版本号查询
  写死 notification authority、导致其它 Provider 永远拿不到版本的问题。

### 11. 横屏名单写入限流
`LandscapeAppsProvider` 的全选写入改为单次 `commit`（原来逐条 `apply`），避免大量异步落盘。

---

## P3：体验与可维护性

- 适配器统一使用 `packageName|userId` 作为勾选键（原实现横屏类型统一按 `-1` 计算，多用户场景错位），
  全选/反选、搜索过滤都在同一套键上进行。
- `TYPE_FLOATING / TYPE_NOTIFICATION / TYPE_BLACKLIST / TYPE_LANDSCAPE` 从 Activity 迁到 ViewModel
  （配置变更后 Activity 重建，静态常量放在 Activity 上不稳），`SettingFragment` 引用同步更新。
- 通知名单读取补 `getAllNotificationKeys()`，与其它列表的返回类型一致。
- 搜索过滤改为 `submitFilter()`，不再走 `updateDate + notifyDataSetChanged` 乱序覆盖置顶顺序。

---

## 遗留 / 建议后续处理
- `Providers` 仍为 `exported=true`（launcher / SystemUI 需要），如需进一步收紧，可改用
  `android:permission` + 自定义 `protectionLevel="signature|privileged"` 的权限，
  但要求被 hook 的第三方进程被授予该权限，实际落地成本高，本次选择“代码层鉴权 + 只读结果”。
- `HiddenApiRestriction`、`FreeformConfig` 等模块本次未涉及。
