package xyz.aezdek.aezprint

import androidx.compose.ui.test.junit4.ComposeContentTestRule

/**
 * Compose's waitUntil measures the virtual main clock under Robolectric, while the fake server and job
 * runner work in real time on background threads; this waits on the wall clock instead.
 */
fun ComposeContentTestRule.awaitReal(
    timeoutMs: Long = 20_000,
    describe: () -> String = { "" },
    condition: () -> Boolean,
) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (true) {
        waitForIdle()
        if (condition()) return
        if (System.nanoTime() > deadline) throw AssertionError("Condition not met within $timeoutMs ms. ${describe()}")
        Thread.sleep(25)
    }
}
