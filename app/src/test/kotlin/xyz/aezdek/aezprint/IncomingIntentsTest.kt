package xyz.aezdek.aezprint

import android.app.Application
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.aezdek.aezprint.navigation.IncomingIntents
import xyz.aezdek.aezprint.navigation.JobDestination
import xyz.aezdek.aezprint.navigation.PrintDestination

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class IncomingIntentsTest {

    @Test
    fun openWithPdfGoesToThePrintScreen() {
        val uri = Uri.parse("content://com.android.providers.downloads.documents/document/42")
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
        assertEquals(PrintDestination(uri = uri.toString()), IncomingIntents.destinationFor(intent))
    }

    @Test
    fun sharedPdfGoesToThePrintScreen() {
        val uri = Uri.parse("content://com.example.files/report.pdf")
        val intent = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
        assertEquals(PrintDestination(uri = uri.toString()), IncomingIntents.destinationFor(intent))
    }

    @Test
    fun notificationDeepLinkOpensTheJob() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("aezprint://job/abc-123"))
        assertEquals(JobDestination("abc-123"), IncomingIntents.destinationFor(intent))
    }

    @Test
    fun launcherIntentHasNoDestination() {
        assertNull(IncomingIntents.destinationFor(Intent(Intent.ACTION_MAIN)))
    }
}
