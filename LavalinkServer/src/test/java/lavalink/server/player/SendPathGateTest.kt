package lavalink.server.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException

class SendPathGateTest {
    private var now = 1_000_000L
    private val probe = ScriptedProbe()
    private val gate = SendPathGate(probe, { now })
    private val server = InetSocketAddress(InetAddress.getLoopbackAddress(), 50_000)
    private val otherServer = InetSocketAddress(InetAddress.getLoopbackAddress(), 50_001)

    @Test
    fun `accepted sends are probed once a second`() {
        assertTrue(gate.mayPull(server))
        now += 500
        assertTrue(gate.mayPull(server))
        now += 499
        assertTrue(gate.mayPull(server))
        assertEquals(1, probe.calls)

        now += 1
        assertTrue(gate.mayPull(server))

        assertEquals(2, probe.calls)
        assertNull(gate.refusedSince)
    }

    @Test
    fun `a refusal holds every poll and is probed again on every poll`() {
        probe.refusing = true
        val firstRefusal = now

        assertFalse(gate.mayPull(server))
        now += 20
        assertFalse(gate.mayPull(server))

        assertEquals(2, probe.calls)
        assertEquals(firstRefusal, gate.refusedSince, "refusedSince stays the first refusal")
        assertEquals("Operation not permitted", gate.lastRefusal?.message)
    }

    @Test
    fun `the first accepted probe ends the hold`() {
        probe.refusing = true
        assertFalse(gate.mayPull(server))
        now += 20
        probe.refusing = false

        assertTrue(gate.mayPull(server))

        assertNull(gate.refusedSince)
    }

    @Test
    fun `a reset gate probes at once and dates a new refusal`() {
        probe.refusing = true
        assertFalse(gate.mayPull(server))
        val firstRefusal = now

        gate.reset()
        assertNull(gate.refusedSince)
        now += 60_000

        assertFalse(gate.mayPull(server))
        assertEquals(2, probe.calls)
        assertEquals(firstRefusal + 60_000, gate.refusedSince)
    }

    @Test
    fun `a new voice server is probed at once`() {
        assertTrue(gate.mayPull(server))
        now += 20
        probe.refusing = true

        assertFalse(gate.mayPull(otherServer))

        assertEquals(2, probe.calls)
    }

    @Test
    fun `a clock that went back probes again`() {
        assertTrue(gate.mayPull(server))
        now -= 5_000

        gate.mayPull(server)

        assertEquals(2, probe.calls)
    }

    @Test
    fun `the OS refusal of a send is returned`() {
        // Without SO_BROADCAST the kernel refuses any send to the limited broadcast address.
        val refusal = UdpSendProbe().refusal(InetSocketAddress("255.255.255.255", 50_000))

        assertNotNull(refusal)
    }

    @Test
    fun `an accepted probe reaches the server as an empty datagram`() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { socket ->
            socket.soTimeout = 2_000

            val refusal = UdpSendProbe().refusal(socket.localSocketAddress as InetSocketAddress)

            assertNull(refusal)
            val datagram = DatagramPacket(ByteArray(16), 16)
            socket.receive(datagram)
            assertEquals(0, datagram.length, "probe length")
        }
    }

    @Test
    fun `an address that cannot be asked about is not a refusal`() {
        assertNull(UdpSendProbe().refusal(InetSocketAddress.createUnresolved("voice.invalid", 50_000)))
    }

    private class ScriptedProbe : UdpSendProbe() {
        var refusing = false
        var calls = 0

        override fun refusal(address: InetSocketAddress): IOException? {
            calls++
            return if (refusing) SocketException("Operation not permitted") else null
        }
    }
}
