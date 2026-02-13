package io.relimus.zflow.xposed.hook.utils

/**
 * 一个强转工具函数，用于替代 'as' 关键字。
 * 当你100%确定类型不会错时使用，效果等同于 'as T'。
 *
 * @return 转换成功后的 T 类型对象，如果转换失败则抛出 ClassCastException。
 */
inline fun <reified T> Any?.cast(): T {
    return this as T
}