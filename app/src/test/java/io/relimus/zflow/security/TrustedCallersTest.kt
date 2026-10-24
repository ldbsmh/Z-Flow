package io.relimus.zflow.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 白名单序列化的纯函数测试：SharedPreferences 里存的是按行拼接的包名，
 * 解析必须容忍空串 / 空行 / 重复项 / 首尾空白。
 */
class TrustedCallersTest {

    @Test
    fun parseNullReturnsEmpty() {
        assertTrue(TrustedCallers.parse(null).isEmpty())
    }

    @Test
    fun parseBlankReturnsEmpty() {
        assertTrue(TrustedCallers.parse("").isEmpty())
        assertTrue(TrustedCallers.parse(" \n \n").isEmpty())
    }

    @Test
    fun parseTrimsAndSkipsBlankLines() {
        assertEquals(
            setOf("io.github.lumiagg.gestureevo", "com.example.tool"),
            TrustedCallers.parse(" io.github.lumiagg.gestureevo \n\ncom.example.tool\n")
        )
    }

    @Test
    fun serializeRoundTripsThroughParse() {
        val packages = listOf("com.example.tool", "io.github.lumiagg.gestureevo")
        assertEquals(packages.toSet(), TrustedCallers.parse(TrustedCallers.serialize(packages)))
    }

    @Test
    fun serializeDedupesAndDropsBlankEntries() {
        assertEquals(
            "com.example.tool\ncom.other",
            TrustedCallers.serialize(listOf(" com.example.tool ", "", "com.other", "com.example.tool"))
        )
    }
}
