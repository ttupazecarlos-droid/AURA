package com.example.aura.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ShuffleTest {

    @Test
    fun `no repite pistas ya reproducidas`() {
        val index = pickShuffleIndex(
            queueSize = 4,
            currentIndex = 0,
            playedIndices = setOf(0, 1, 2),
            repeatAll = false,
            random = Random(0)
        )
        assertEquals(3, index)
    }

    @Test
    fun `termina la cola con repeat OFF cuando ya sono todo`() {
        val index = pickShuffleIndex(
            queueSize = 4,
            currentIndex = 2,
            playedIndices = setOf(0, 1, 2, 3),
            repeatAll = false
        )
        assertNull(index)
    }

    @Test
    fun `reinicia el ciclo con repeat ALL`() {
        val index = pickShuffleIndex(
            queueSize = 4,
            currentIndex = 2,
            playedIndices = setOf(0, 1, 2, 3),
            repeatAll = true,
            random = Random(0)
        )
        assertTrue(index in setOf(0, 1, 3))
    }

    @Test
    fun `una sola pista se repite con repeat ALL y para con repeat OFF`() {
        assertEquals(0, pickShuffleIndex(1, 0, setOf(0), repeatAll = true))
        assertNull(pickShuffleIndex(1, 0, setOf(0), repeatAll = false))
    }

    @Test
    fun `cola vacia no devuelve indice`() {
        assertNull(pickShuffleIndex(0, 0, emptySet(), repeatAll = true))
    }
}
