package io.relimus.zflow.xposed.utils

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Reflection utilities for Xposed hook context.
 */
object ReflectUtils {
    /**
     * Get field value from object.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> Any.getField(fieldName: String): T {
        val field = findField(this.javaClass, fieldName)
        field.isAccessible = true
        return field.get(this) as T
    }

    /**
     * Invoke method on object.
     */
    fun Any.invokeMethod(methodName: String, vararg args: Any?): Any? {
        val paramTypes = args.map { it?.javaClass ?: Any::class.java }.toTypedArray()
        val method = findMethod(this.javaClass, methodName, *paramTypes)
        method.isAccessible = true
        return method.invoke(this, *args)
    }

    /**
     * Invoke method with explicit parameter types.
     */
    fun Any.invokeMethodAs(methodName: String, paramTypes: Array<Class<*>>, vararg args: Any?): Any? {
        val method = findMethod(this.javaClass, methodName, *paramTypes)
        method.isAccessible = true
        return method.invoke(this, *args)
    }

    /**
     * Create new instance with args.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> Class<*>.newInstance(paramTypes: Array<Class<*>>, vararg args: Any?): T {
        val constructor = this.getDeclaredConstructor(*paramTypes)
        constructor.isAccessible = true
        return constructor.newInstance(*args) as T
    }

    private fun findField(clazz: Class<*>, fieldName: String): Field {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName)
            } catch (e: NoSuchFieldException) {
                current = current.superclass
            }
        }
        throw NoSuchFieldException("Field $fieldName not found in $clazz")
    }

    private fun findMethod(clazz: Class<*>, methodName: String, vararg paramTypes: Class<*>): Method {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                return current.getDeclaredMethod(methodName, *paramTypes)
            } catch (e: NoSuchMethodException) {
                // Try with boxed/unboxed type conversion
                for (method in current.declaredMethods) {
                    if (method.name == methodName && method.parameterCount == paramTypes.size) {
                        if (isParamTypesCompatible(method.parameterTypes, paramTypes)) {
                            return method
                        }
                    }
                }
                current = current.superclass
            }
        }
        throw NoSuchMethodException("Method $methodName not found in $clazz")
    }

    private fun isParamTypesCompatible(expected: Array<Class<*>>, actual: Array<out Class<*>>): Boolean {
        if (expected.size != actual.size) return false
        for (i in expected.indices) {
            if (!isTypeCompatible(expected[i], actual[i])) {
                return false
            }
        }
        return true
    }

    private fun isTypeCompatible(expected: Class<*>, actual: Class<*>): Boolean {
        if (expected == actual) return true
        if (expected.isAssignableFrom(actual)) return true
        // Handle primitive/wrapper type conversion
        return when {
            expected == Int::class.javaPrimitiveType && actual == Int::class.java -> true
            expected == Int::class.java && actual == Int::class.javaPrimitiveType -> true
            expected == Long::class.javaPrimitiveType && actual == Long::class.java -> true
            expected == Long::class.java && actual == Long::class.javaPrimitiveType -> true
            expected == Boolean::class.javaPrimitiveType && actual == Boolean::class.java -> true
            expected == Boolean::class.java && actual == Boolean::class.javaPrimitiveType -> true
            expected == Float::class.javaPrimitiveType && actual == Float::class.java -> true
            expected == Float::class.java && actual == Float::class.javaPrimitiveType -> true
            expected == Double::class.javaPrimitiveType && actual == Double::class.java -> true
            expected == Double::class.java && actual == Double::class.javaPrimitiveType -> true
            else -> false
        }
    }
}
