// IWheelLauncher.aidl
package io.relimus.zflowwheel.wheel;

/**
 * 由 Z-Flow（小窗模块）的 system_server 侧实现，轮盘项目只负责调用。
 *
 * 为什么不直接把 IFreeformManager 拿来用：那个接口有 14 个参数，全部是小窗
 * 尺寸/DPI/停靠样式的调参，轮盘一个都不关心。把它包一层，轮盘侧只需要
 * 「包名 + Activity + 用户」三件事，参数变更也不会波及轮盘。
 */
interface IWheelLauncher {
    /** 版本号，便于以后双向升级时做兼容判断。 */
    int getApiVersion();

    /** system_server 侧是否已就绪（虚拟屏/窗口服务都可用）。 */
    boolean isReady();

    /**
     * 用自由窗口打开一个 Activity。
     * @return true 表示请求已被受理（不代表窗口已出图，出图失败会另行广播）。
     */
    boolean launchFreeform(String packageName, String activityName, int userId);
}
