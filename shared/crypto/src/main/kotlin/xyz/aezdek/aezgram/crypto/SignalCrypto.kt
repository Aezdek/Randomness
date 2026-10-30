package xyz.aezdek.aezgram.crypto

import com.ionspin.kotlin.crypto.box.Box

/**
 * Ephemeral end-to-end messages relayed by the server's WebSocket: call offers/answers/ICE, live-stream chat.
 * Sealed to the recipient and signed by the sender, so the server can neither read nor forge them. In particular
 * the DTLS fingerprints inside SDP are authenticated end to end, which is what makes the WebRTC media E2EE even
 * through a TURN relay.
 */
object SignalCrypto {
    const val MAX_AGE_MS = 120_000L

    class Sealed(val sealed: ByteArray, val signature: ByteArray, val sentAt: Long)

    fun seal(message: ByteArray, from: String, to: String, sentAt: Long, sender: AccountKeys, recipientBoxPk: ByteArray): Sealed {
        Sodium.init()
        val sealed = Box.seal(message.asUByteArray(), recipientBoxPk.asUByteArray()).asByteArray()
        return Sealed(sealed, Signing.sign(signingInput(from, to, sentAt, sealed), sender.signSk), sentAt)
    }

    /** Returns the plaintext, or throws if the signature is wrong, the message is stale, or it was not for us. */
    fun open(sealed: Sealed, from: String, to: String, senderSignPk: ByteArray, me: AccountKeys, now: Long): ByteArray {
        if (kotlin.math.abs(now - sealed.sentAt) > MAX_AGE_MS) throw CryptoException("stale signal")
        if (!Signing.verify(signingInput(from, to, sealed.sentAt, sealed.sealed), sealed.signature, senderSignPk)) {
            throw CryptoException("bad signal signature")
        }
        return try {
            Box.sealOpen(sealed.sealed.asUByteArray(), me.boxPk.asUByteArray(), me.boxSk.asUByteArray()).asByteArray()
        } catch (e: Exception) {
            throw CryptoException("signal did not open", e)
        }
    }

    fun signingInput(from: String, to: String, sentAt: Long, sealed: ByteArray): ByteArray =
        utf8("aezgram/v1/signal\n$from\n$to\n$sentAt\n${B64.encode(Sodium.blake2b(32, sealed))}")
}
