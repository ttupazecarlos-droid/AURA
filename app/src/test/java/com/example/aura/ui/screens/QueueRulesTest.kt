package com.example.aura.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueRulesTest {
    @Test
    fun `shuffle shows candidates before the current index with their real queue indices`() {
        assertEquals(listOf(0, 1), visibleQueueIndices(3, 2, true))
    }

    @Test
    fun `sequential queue only shows following tracks`() {
        assertEquals(listOf(2, 3), visibleQueueIndices(4, 1, false))
        assertEquals(emptyList<Int>(), visibleQueueIndices(0, 0, true))
    }
}
