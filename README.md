# Z-Flow

> Flyme Style Edition based on Mi-Freeform

Z-Flow 是一个基于 Xposed 的自由窗口（Freeform）增强模块，能够将大部分应用以小窗形式显示。当前支持：

- 通过全局侧边栏以小窗模式打开收藏应用
- 通过常驻通知打开收藏应用
- 通过磁贴（Tile）打开收藏应用
- 让发送通知的应用自动以自由窗口模式打开

## 新功能（相对原版本）

1. **桌面图标长按打开小窗** — 在桌面上长按任意应用图标，即可快速以小窗模式启动该应用。
2. **小窗贴边样式优化** — 重新设计了小窗拖至屏幕边缘时的贴边视觉效果，并支持将小窗拖到屏幕底部来关闭应用。
3. **小窗数量限制可调** — 同时存在的小窗数量支持调节，范围 **1～5 个**，可在设置中按需配置。
4. **小窗黑名单** — 支持配置黑名单，使指定应用无法以小窗模式打开。
5. **小窗横屏应用选择** — 支持指定哪些应用以小窗形式打开时自动横屏显示，方便视频类、游戏类等横屏场景。
6. **通知接管方案更新** — 拦截并接管应用通知的小窗方案已重构，基于 [MoreBubbleButton](https://github.com/TYOPXN360/MoreBubbleButton) 项目修改实现，采用消息气泡按钮机制打开通知对应的应用界面。

## 下载

[Release](https://github.com/relimus/Z-Flow/releases/)

## 使用前提

Z-Flow 依赖 Xposed 框架运行，需确保设备已安装并激活 Xposed/LSPosed。部分功能可能还需要 Shizuku 或无障碍权限的支持。

## 依赖库

| 库 | 说明 |
|---|---|
| [AppIconLoader](https://github.com/zhanghai/AppIconLoader) | 应用图标加载 |
| [Glide](https://github.com/bumptech/glide) | 图片加载 |
| [RikkaX](https://github.com/RikkaApps/RikkaX) | 系统 API 兼容 |
| [Shizuku](https://github.com/RikkaApps/Shizuku) | 特权 API 调用 |
| [TinyPinyin](https://github.com/promeG/TinyPinyin) | 拼音搜索支持 |
| [Xposed](https://github.com/rovo89/Xposed) | 模块框架 |
| [MoreBubbleButton](https://github.com/TYOPXN360/MoreBubbleButton) | 消息气泡按钮（通知接管方案参考） |

## 许可

- Copyright (C) 2021-2022 sunshine0523
- Copyright (C) 2023 DtHnAme
- Copyright (C) 2024-2026 relimus

本程序为自由软件：你可以根据 GNU 通用公共许可证（由自由软件基金会发布）的条款，按第 3 版或（由你选择）任何更新版本重新分发和/或修改。

本程序的分发是希望它有用，但没有任何保证；甚至没有对适销性或特定用途适用性的默示保证。详见 GNU 通用公共许可证。

更多详情请参阅 GNU 通用公共许可证。
