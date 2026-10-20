package io.relimus.zflow.xposed.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowIdentityRemovalRegressionTest {
    private data class Window(val displayId: Int)

    @Test
    fun unregisteredWindowBeforeDisplayCreationDoesNotRemoveAnotherInstance() {
        val registered = Window(-1)
        val failedConstruction = Window(-1)
        val windows = mutableListOf(registered)

        assertFalse(windows.removeWindowIdentity(failedConstruction))
        assertEquals(1, windows.size)
        assertSame(registered, windows.single())
    }

    @Test
    fun staleInstanceDoesNotRemoveReplacementOnReusedDisplay() {
        val stale = Window(42)
        val replacement = Window(42)
        val windows = mutableListOf(replacement)

        assertFalse(windows.removeWindowIdentity(stale))
        assertSame(replacement, windows.single())
    }

    @Test
    fun removesExactInstanceEvenWhenAnotherObjectIsEqual() {
        val other = Window(42)
        val target = Window(42)
        val windows = mutableListOf(other, target)
        assertEquals(other, target)

        assertTrue(windows.removeWindowIdentity(target))
        assertEquals(1, windows.size)
        assertSame(other, windows.single())
    }

    @Test
    fun repeatedRemovalIsANoOp() {
        val target = Window(7)
        val survivor = Window(8)
        val windows = mutableListOf(target, survivor)

        assertTrue(windows.removeWindowIdentity(target))
        assertFalse(windows.removeWindowIdentity(target))
        assertSame(survivor, windows.single())
    }

    @Test
    fun emptyRegistryIsSafe() {
        assertFalse(mutableListOf<Window>().removeWindowIdentity(Window(-1)))
    }
}
