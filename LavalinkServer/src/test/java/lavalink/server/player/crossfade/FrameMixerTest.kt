package lavalink.server.player.crossfade

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

class FrameMixerTest {
    private val spc = 960
    private val channels = 2
    private val total = spc * channels

    private fun filled(value: Int): ShortBuffer = ShortBuffer.allocate(total).also { b ->
        for (i in 0 until total) b.put(i, value.toShort())
    }

    @Test
    fun `B's gain rises from 0 to 1 and A's falls from 1 to 0, monotonic, summing to 1`() {
        // A = 10000 and B = 0 read back gA; A = 0 and B = 10000 read back gB.
        val n = 10
        val a = filled(10000)
        val zero = filled(0)
        val out = ShortBuffer.allocate(total)
        var lastA = Int.MAX_VALUE
        var lastB = Int.MIN_VALUE
        for (k in 0 until n) {
            FrameMixer.mixLinear(a, zero, out, k, n)
            val outA = ShortArray(total) { out.get(it) }
            FrameMixer.mixLinear(zero, a, out, k, n)
            val outB = ShortArray(total) { out.get(it) }
            for (i in 0 until spc) {
                val gA = outA[i * channels].toInt()
                val gB = outB[i * channels].toInt()
                assertTrue(gA <= lastA, "gA must not rise (k=$k i=$i)")
                assertTrue(gB >= lastB, "gB must not fall (k=$k i=$i)")
                assertTrue(abs(gA + gB - 10000) <= 1, "gA + gB must be 1 (k=$k i=$i): $gA + $gB")
                lastA = gA
                lastB = gB
            }
        }
        FrameMixer.mixLinear(a, zero, out, 0, n)
        assertEquals(10000, out.get(0).toInt(), "gA starts at 1")
        assertTrue(lastA <= 2, "gA ends at 0, got $lastA / 10000")
        assertTrue(lastB >= 9998, "gB ends at 1, got $lastB / 10000")
    }

    @Test
    fun `constants +10000 and -10000 mix to the expected value at k = 0, n over 2 and n - 1`() {
        val n = 10
        val a = filled(10000)
        val b = filled(-10000)
        val out = ShortBuffer.allocate(total)
        for (k in listOf(0, n / 2, n - 1)) {
            FrameMixer.mixLinear(a, b, out, k, n)
            for (i in listOf(0, 1, spc / 2, spc - 1)) {
                val gB = (k * spc + i).toDouble() / (n * spc)
                val expected = (10000 * (1 - gB) - 10000 * gB).roundToInt()
                for (c in 0 until channels) {
                    val actual = out.get(i * channels + c).toInt()
                    assertTrue(abs(actual - expected) <= 1, "k=$k i=$i c=$c: expected $expected, got $actual")
                }
            }
        }
    }

    @Test
    fun `the mix saturates at the Short limits and never wraps around`() {
        // gA + gB = 1, so a mix of in-range inputs stays in range; at the extremes it must not wrap.
        val out = ShortBuffer.allocate(total)
        for (k in listOf(0, 4, 9)) {
            FrameMixer.mixLinear(filled(32767), filled(32767), out, k, 10)
            for (i in 0 until total) assertEquals(32767, out.get(i).toInt(), "k=$k index=$i")
            FrameMixer.mixLinear(filled(-32768), filled(-32768), out, k, 10)
            for (i in 0 until total) assertEquals(-32768, out.get(i).toInt(), "k=$k index=$i")
        }
        assertEquals(Short.MAX_VALUE, FrameMixer.clamp(40000.0))
        assertEquals(Short.MIN_VALUE, FrameMixer.clamp(-40000.0))
        assertEquals(Short.MAX_VALUE, FrameMixer.clamp(32767.6))
        assertEquals(1234, FrameMixer.clamp(1234.4).toInt())
        assertEquals(-1235, FrameMixer.clamp(-1234.6).toInt())
    }

    @Test
    fun `both channels get the same gain`() {
        val a = ShortBuffer.allocate(total)
        val b = ShortBuffer.allocate(total)
        for (i in 0 until spc) {
            a.put(i * 2, 8000); a.put(i * 2 + 1, 8000)
            b.put(i * 2, -4000); b.put(i * 2 + 1, -4000)
        }
        val out = ShortBuffer.allocate(total)
        FrameMixer.mixLinear(a, b, out, 3, 7)
        for (i in 0 until spc) {
            assertEquals(out.get(i * 2), out.get(i * 2 + 1), "sample $i")
        }
    }
}
