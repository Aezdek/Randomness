package xyz.aezdek.hush.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import xyz.aezdek.hush.protocol.ServerState
import kotlin.concurrent.thread

fun main() {
    val config = ServerConfig.fromEnv()
    val vault = Vault(config)
    val key = vault.unlockWithMachineKey() ?: waitForSetup(config, vault)
    embeddedServer(CIO, port = config.port, host = config.host) {
        hush(config.copy(dbKey = key))
    }.start(wait = true)
}

/**
 * Serves the setup server (Setup.kt) until the server password is set or entered, or until the machine key appears
 * (sealed to the TPM, it's written at boot and may come after the container starts). Then it steps aside for the
 * real server on the same port.
 */
private fun waitForSetup(config: ServerConfig, vault: Vault): ByteArray {
    val log = LoggerFactory.getLogger("hush")
    val unlocked = CompletableDeferred<ByteArray>()
    val setup = embeddedServer(CIO, port = config.port, host = config.host) {
        setupServer(config, vault) { unlocked.complete(it) }
    }.start(wait = false)
    thread(isDaemon = true, name = "machine-key") {
        while (!unlocked.isCompleted) {
            Thread.sleep(5_000)
            if (vault.state() == ServerState.LOCKED) runCatching { vault.unlockWithMachineKey() }.getOrNull()?.let { unlocked.complete(it) }
        }
    }
    val key = runBlocking { unlocked.await() }
    // Let the answer to update.sh go out before the port changes hands.
    Thread.sleep(500)
    setup.stop(gracePeriodMillis = 200, timeoutMillis = 2_000)
    log.info("Unlocked; starting Hush")
    return key
}
