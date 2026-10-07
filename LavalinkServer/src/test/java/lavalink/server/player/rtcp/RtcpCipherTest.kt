package lavalink.server.player.rtcp

import io.netty.buffer.Unpooled
import moe.kyokobot.koe.internal.crypto.AEADAES256GCMRTPSizeEncryptionMode
import moe.kyokobot.koe.internal.crypto.AEADXChaCha20Poly1305RTPSizeEncryptionMode
import moe.kyokobot.koe.internal.crypto.EncryptionMode
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class RtcpCipherTest {
    private val random = SecureRandom()
    private val key = ByteArray(32).also { random.nextBytes(it) }
    private val receiverReport = hex("81c90007 00004092 00004092 40000123 00012345 000001e0 00000000 00000000")

    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Discord's layout, from an AES-GCM implementation of its own (the JDK's), nonce counter 7. */
    private fun sealedLikeDiscord(plain: ByteArray, key: ByteArray = this.key): ByteArray {
        val nonce = byteArrayOf(7, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(plain, 0, 8)
        return plain.copyOf(8) + cipher.doFinal(plain, 8, plain.size - 8) + nonce.copyOf(4)
    }

    @Test
    fun `opens an AES-GCM receiver report in Discord's layout`() {
        val packet = sealedLikeDiscord(receiverReport)
        assertArrayEquals(receiverReport, RtcpCipher().open(RtcpCipher.AES_GCM, key, packet, 0, packet.size))
    }

    @Test
    fun `opens a packet in the middle of a buffer`() {
        val packet = byteArrayOf(9, 9, 9) + sealedLikeDiscord(receiverReport) + byteArrayOf(9)
        assertArrayEquals(receiverReport, RtcpCipher().open(RtcpCipher.AES_GCM, key, packet, 3, packet.size - 4))
    }

    @Test
    fun `refuses what this key did not seal or what was altered`() {
        val other = ByteArray(32).also { random.nextBytes(it) }
        val packet = sealedLikeDiscord(receiverReport)
        val cipher = RtcpCipher()

        assertNull(cipher.open(RtcpCipher.AES_GCM, other, packet, 0, packet.size), "another connection's key")
        // The clear header is authenticated: a report claiming another SSRC does not open.
        val altered = packet.copyOf().also { it[7] = (it[7] + 1).toByte() }
        assertNull(cipher.open(RtcpCipher.AES_GCM, key, altered, 0, altered.size), "altered header")
        assertNull(cipher.open(RtcpCipher.AES_GCM, key, packet, 0, 8 + 16 + 3), "shorter than header, tag and nonce")
        assertNull(cipher.open("xsalsa20_poly1305", key, packet, 0, packet.size), "a mode Discord no longer offers")
        // Still usable after a failure.
        assertArrayEquals(receiverReport, cipher.open(RtcpCipher.AES_GCM, key, packet, 0, packet.size))
    }

    /** Koe's encryptor, the one whose RTP Discord accepts: same nonce suffix, tag and associated data. */
    private fun sealedByKoe(mode: EncryptionMode, rtpHeader: ByteArray, payload: ByteArray): ByteArray {
        val output = Unpooled.buffer().writeBytes(rtpHeader)
        mode.box(Unpooled.wrappedBuffer(payload), payload.size, output, key)
        return ByteArray(output.readableBytes()).also { output.getBytes(0, it) }
    }

    @Test
    fun `AES-GCM is the inverse of Koe's encryptor`() {
        val header = hex("80780001 000003c0 00004092")
        val payload = ByteArray(160).also { random.nextBytes(it) }
        val packet = sealedByKoe(AEADAES256GCMRTPSizeEncryptionMode(), header, payload)

        assertArrayEquals(header + payload, RtcpCipher().open(RtcpCipher.AES_GCM, key, packet, 0, packet.size, 12))
    }

    @Test
    fun `XChaCha20-Poly1305 is the inverse of Koe's encryptor`() {
        val header = hex("80780001 000003c0 00004092")
        val cipher = RtcpCipher()
        // Koe's implementation is its own (not the JDK's): several packets, nonces and lengths.
        val mode = AEADXChaCha20Poly1305RTPSizeEncryptionMode()
        for (size in listOf(1, 63, 64, 65, 160, 1000)) {
            val payload = ByteArray(size).also { random.nextBytes(it) }
            val packet = sealedByKoe(mode, header, payload)
            assertArrayEquals(header + payload, cipher.open(RtcpCipher.XCHACHA20_POLY1305, key, packet, 0, packet.size, 12), "$size bytes")
        }
    }

    @Test
    fun `HChaCha20 test vector`() {
        // draft-irtf-cfrg-xchacha-03 section 2.2.1
        val key = ByteArray(32) { it.toByte() }
        val nonce = hex("000000090000004a0000000031415927")
        assertArrayEquals(
            hex("82413b42 27b27bfe d30e4250 8a877d73 a0f9e4d5 8a74a853 c12ec413 26d3ecdc"),
            hChaCha20(key, nonce)
        )
    }
}
