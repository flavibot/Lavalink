package lavalink.server.player.rtcp

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Transport encryption of the RTCP packets Discord sends on the media socket.
 *
 * In the `*_rtpsize` AEAD modes an RTCP packet is protected like SRTCP (RFC 3711
 * section 3.4): the first 8 bytes (header and sender SSRC) stay clear and are the
 * associated data, the rest is encrypted, followed by the 16-byte tag and a 4-byte
 * nonce suffix. The suffix is the first 4 bytes of the nonce, the remaining bytes
 * are zero: the layout Koe's encryption modes write for RTP.
 *
 * Koe only encrypts, so the decryption is here. Not thread safe: one instance per
 * media connection ([RtcpReceiver] serializes its use).
 *
 * A report can be tried on a key that is not its own ([RtcpRouter] falls back to
 * trying every connection of the voice server), with a nonce counter that key's
 * own reports also use: Discord counts from 0 on every connection, so two opened
 * in the same second share their counters.
 */
class RtcpCipher {
    companion object {
        const val AES_GCM = "aead_aes256_gcm_rtpsize"
        const val XCHACHA20_POLY1305 = "aead_xchacha20_poly1305_rtpsize"
        const val PLAIN = "plain"

        const val TAG_LENGTH = 16
        const val NONCE_SUFFIX_LENGTH = 4

        /** The modes [open] can read: the two Discord still offers, and Koe's test-only plain mode. */
        val SUPPORTED_MODES = setOf(AES_GCM, XCHACHA20_POLY1305, PLAIN)
    }

    // The JDK lets a GCM decryption re-initialise with the key and nonce it just
    // used, so one instance serves every packet. Not ChaCha20-Poly1305: see below.
    private var aes: Cipher? = null

    /**
     * @return the clear header followed by the decrypted body, or null when the
     * packet is too short, the mode unknown, or the tag does not authenticate it
     * (a packet that was not encrypted with this key).
     */
    fun open(mode: String, key: ByteArray, packet: ByteArray, offset: Int, length: Int): ByteArray? =
        open(mode, key, packet, offset, length, RtcpParser.HEADER_LENGTH)

    /** [header]: the clear bytes, 8 for RTCP; 12 for an RTP packet without extension, which tests compare with Koe. */
    internal fun open(mode: String, key: ByteArray, packet: ByteArray, offset: Int, length: Int, header: Int): ByteArray? {
        if (mode == PLAIN) return packet.copyOfRange(offset, offset + length)
        if (mode !in SUPPORTED_MODES || length < header + TAG_LENGTH + NONCE_SUFFIX_LENGTH) return null

        val sealedLength = length - header - NONCE_SUFFIX_LENGTH
        val suffixAt = offset + length - NONCE_SUFFIX_LENGTH
        return try {
            val body = when (mode) {
                AES_GCM -> {
                    val nonce = ByteArray(12)
                    System.arraycopy(packet, suffixAt, nonce, 0, NONCE_SUFFIX_LENGTH)
                    val cipher = aes ?: Cipher.getInstance("AES/GCM/NoPadding").also { aes = it }
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH * 8, nonce))
                    cipher.updateAAD(packet, offset, header)
                    cipher.doFinal(packet, offset + header, sealedLength)
                }

                else -> {
                    val nonce = ByteArray(24)
                    System.arraycopy(packet, suffixAt, nonce, 0, NONCE_SUFFIX_LENGTH)
                    // XChaCha20-Poly1305 = ChaCha20-Poly1305 (RFC 8439) under a subkey
                    // HChaCha20 derives from the first 16 nonce bytes, with the last
                    // 8 bytes as the nonce (draft-irtf-cfrg-xchacha section 2.3).
                    val subKey = hChaCha20(key, nonce)
                    val ietfNonce = ByteArray(12)
                    System.arraycopy(nonce, 16, ietfNonce, 4, 8)
                    // A new instance per packet: the JDK's ChaCha20-Poly1305 refuses to be
                    // re-initialised with the key and nonce of its previous init, decryption
                    // included ("Matching key and nonce from previous initialization"). The
                    // ChaCha nonce here is always zero, so the subkey alone is that pair: a
                    // report tried on our key and refused would make our own report with
                    // the same counter throw, and be dropped as if it were not ours.
                    val cipher = Cipher.getInstance("ChaCha20-Poly1305")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(subKey, "ChaCha20"), IvParameterSpec(ietfNonce))
                    cipher.updateAAD(packet, offset, header)
                    cipher.doFinal(packet, offset + header, sealedLength)
                }
            }
            ByteArray(header + body.size).also {
                System.arraycopy(packet, offset, it, 0, header)
                System.arraycopy(body, 0, it, header, body.size)
            }
        } catch (e: GeneralSecurityException) {
            null
        }
    }
}

/**
 * HChaCha20 (draft-irtf-cfrg-xchacha section 2.2): 20 ChaCha rounds over the key
 * and the first 16 nonce bytes, keeping words 0-3 and 12-15 as the subkey.
 */
internal fun hChaCha20(key: ByteArray, nonce: ByteArray): ByteArray {
    val s = IntArray(16)
    s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
    for (i in 0 until 8) s[4 + i] = le32(key, i * 4)
    for (i in 0 until 4) s[12 + i] = le32(nonce, i * 4)
    repeat(10) {
        quarterRound(s, 0, 4, 8, 12); quarterRound(s, 1, 5, 9, 13)
        quarterRound(s, 2, 6, 10, 14); quarterRound(s, 3, 7, 11, 15)
        quarterRound(s, 0, 5, 10, 15); quarterRound(s, 1, 6, 11, 12)
        quarterRound(s, 2, 7, 8, 13); quarterRound(s, 3, 4, 9, 14)
    }
    val out = ByteArray(32)
    intArrayOf(0, 1, 2, 3, 12, 13, 14, 15).forEachIndexed { i, word ->
        val v = s[word]
        for (b in 0 until 4) out[i * 4 + b] = (v ushr (8 * b)).toByte()
    }
    return out
}

private fun quarterRound(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
    s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 16)
    s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 12)
    s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 8)
    s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 7)
}

private fun le32(b: ByteArray, at: Int) =
    (b[at].toInt() and 0xff) or (b[at + 1].toInt() and 0xff shl 8) or
        (b[at + 2].toInt() and 0xff shl 16) or (b[at + 3].toInt() and 0xff shl 24)
