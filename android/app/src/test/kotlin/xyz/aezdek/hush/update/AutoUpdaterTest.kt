package xyz.aezdek.hush.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.aezdek.hush.protocol.AppVersion

class AutoUpdaterTest {
    private fun server(code: Int, name: String) = AppVersion(code, name, sha256 = "00", size = 1, url = "/download/hush.apk")

    @Test
    fun versionNamesCompareNumberByNumber() {
        assertTrue(AutoUpdater.compareVersions("1.0.10", "1.0.9") > 0)
        assertTrue(AutoUpdater.compareVersions("1.1.0", "1.0.99") > 0)
        assertTrue(AutoUpdater.compareVersions("1.0.9", "1.0.10") < 0)
        assertEquals(0, AutoUpdater.compareVersions("1.0.23", "1.0.23"))
        assertEquals(0, AutoUpdater.compareVersions("1.0", "1.0.0"))
        assertTrue(AutoUpdater.compareVersions("2", "1.9.9") > 0)
    }

    @Test
    fun onlyALaterBuildIsAnUpdate() {
        assertTrue(AutoUpdater.isNewer(server(24, "1.0.24"), code = 23, name = "1.0.23"))
        assertFalse(AutoUpdater.isNewer(server(23, "1.0.23"), code = 23, name = "1.0.23"))
        // An older build on the server (say, a rollback) is never "installed" over a newer one: Android would refuse it.
        assertFalse(AutoUpdater.isNewer(server(22, "1.0.22"), code = 23, name = "1.0.23"))
        // Same build number, later name: still an update.
        assertTrue(AutoUpdater.isNewer(server(23, "1.0.24"), code = 23, name = "1.0.23"))
    }
}
