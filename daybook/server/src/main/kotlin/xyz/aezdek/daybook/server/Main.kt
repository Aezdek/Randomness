package xyz.aezdek.daybook.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

/**
 * Configuration comes from the environment:
 *
 * - `DAYBOOK_TOKEN` (required): the shared access token, at least 24 characters. `openssl rand -hex 32` makes a good one.
 * - `DAYBOOK_DB`: path of the SQLite file. Defaults to `./data/daybook.db`.
 * - `DAYBOOK_HOST` and `DAYBOOK_PORT`: where to listen. Default `0.0.0.0:8080`.
 */
fun main() {
    val log = LoggerFactory.getLogger("xyz.aezdek.daybook.server")
    val token = System.getenv("DAYBOOK_TOKEN")?.trim().orEmpty()
    if (token.length < 24) {
        log.error("Set DAYBOOK_TOKEN to a secret of at least 24 characters, for example the output of `openssl rand -hex 32`.")
        exitProcess(2)
    }
    val dbPath = System.getenv("DAYBOOK_DB")?.takeIf { it.isNotBlank() } ?: "data/daybook.db"
    val host = System.getenv("DAYBOOK_HOST")?.takeIf { it.isNotBlank() } ?: "0.0.0.0"
    val port = System.getenv("DAYBOOK_PORT")?.toIntOrNull() ?: 8080

    val store = ServerStore.open(dbPath)
    log.info("Daybook sync server listening on {}:{}, data in {}", host, port, dbPath)
    embeddedServer(CIO, host = host, port = port) {
        daybookModule(token, store)
    }.start(wait = true)
}
