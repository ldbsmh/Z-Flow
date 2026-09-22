package io.relimus.zflow.xposed.hook.utils;

/**
 * 模块激活状态标记。
 *
 * 本类的 checkXposed() 由 HookMyself 在宿主进程中被 hook，
 * hook 命中后返回 true，UI 侧据此判断 Xposed 模块是否已生效。
 * 未命中时恒为 false，因此这里不需要任何真实逻辑。
 */
public class HookTest {
    private HookTest() {}

    public static boolean checkXposed() {
        return false;
    }
}
