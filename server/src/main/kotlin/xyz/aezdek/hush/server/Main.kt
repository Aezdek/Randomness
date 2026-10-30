package xyz.aezdek.hush.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

fun main() {
    val config = ServerConfig.fromEnv()
    embeddedServer(CIO, port = config.port, host = config.host) {
        hush(config)
    }.start(wait = true)
}
