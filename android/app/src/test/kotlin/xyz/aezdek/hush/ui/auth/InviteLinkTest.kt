package xyz.aezdek.hush.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.aezdek.hush.ui.messages.distanceMetres

/** What "Scan an invite" accepts from a QR code: an admin's invite link, and nothing that only looks like one. */
class InviteLinkTest {
    @Test
    fun readsTheServerAndTheCode() {
        assertEquals("https://hush.example.com" to "ABCDE-FGHJK", parseInviteLink("https://hush.example.com/?invite=ABCDE-FGHJK"))
        assertEquals("https://hush.example.com:8443" to "ABCDE-FGHJK", parseInviteLink(" https://hush.example.com:8443/?invite=abcde-fghjk "))
    }

    @Test
    fun refusesWhatIsntAnInvite() {
        assertNull(parseInviteLink("http://hush.example.com/?invite=ABCDE-FGHJK"))
        assertNull(parseInviteLink("https://user:pass@hush.example.com/?invite=ABCDE-FGHJK"))
        assertNull(parseInviteLink("https://hush.example.com/?invite=../../x"))
        assertNull(parseInviteLink("https://hush.example.com/"))
        assertNull(parseInviteLink("hush:verify:someone"))
        assertNull(parseInviteLink("javascript:alert(1)"))
    }

    @Test
    fun liveLocationMovesAreMeasuredInMetres() {
        // Rossio to Praça do Comércio in Lisbon is about 740 m.
        val d = distanceMetres(38.7139, -9.1394, 38.7076, -9.1365)
        assert(d in 700.0..800.0) { "distance $d" }
    }
}
