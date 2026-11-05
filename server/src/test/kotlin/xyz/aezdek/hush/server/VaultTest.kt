package xyz.aezdek.hush.server

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.aezdek.hush.protocol.ApiError
import xyz.aezdek.hush.protocol.Health
import xyz.aezdek.hush.protocol.ServerState
import xyz.aezdek.hush.protocol.SetupRequest
import xyz.aezdek.hush.protocol.SetupStatus
import xyz.aezdek.hush.protocol.UnlockRequest
import xyz.aezdek.hush.protocol.Wire
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientNegotiation

class VaultTest {
    private val password = "correct horse battery staple"

    private fun withDirs(block: (data: File, machineKey: File) -> Unit) {
        val root = Files.createTempDirectory("hush-vault").toFile()
        try {
            val secrets = File(root, "secrets").apply { mkdirs() }
            val machineKey = File(secrets, "machine.key")
            block(File(root, "data"), machineKey)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun newMachineKey(file: File) = file.writeText(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) } + "\n")

    @Test
    fun theDatabaseKeyIsSealedUnderThePasswordAndTheMachineAndNeverStoredInTheClear() = withDirs { data, machineKey ->
        newMachineKey(machineKey)
        val config = ServerConfig(dataDir = data, machineKeyFile = machineKey)
        val vault = Vault(config)
        assertEquals(ServerState.SETUP, vault.state())
        assertNull(vault.unlockWithMachineKey())
        // Before setup nothing is stored but the code update.sh reads.
        val code = vault.setupCode()
        assertTrue(code.matches(Regex("[0-9A-F]{5}(-[0-9A-F]{5}){3}")))
        assertEquals(setOf("setup-code.txt"), data.list()!!.toSet())

        val key = vault.setup(password)
        assertEquals(ServerState.LOCKED, vault.state())
        assertFalse("the setup code is gone once used", config.setupCodeFile.exists())
        val ring = config.keyringFile.readText()
        assertTrue(ring.contains("AezCrypt-keyring-1"))
        assertFalse(ring.contains(key.joinToString("") { "%02x".format(it) }))
        assertFalse(ring.contains(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key)))

        // A restart on this machine unlocks by itself; the password opens it too; a wrong one doesn't.
        assertArrayEquals(key, Vault(config).unlockWithMachineKey())
        assertArrayEquals(key, Vault(config).unlock(password))
        assertNull(Vault(config).unlock("not the password at all"))

        // New hardware (another machine key): it can't unlock by itself until the password reseals it there.
        newMachineKey(machineKey)
        assertNull(Vault(config).unlockWithMachineKey())
        assertArrayEquals(key, Vault(config).unlock(password))
        assertArrayEquals(key, Vault(config).unlockWithMachineKey())
    }

    @Test
    fun aShortPasswordIsRefused() = withDirs { data, machineKey ->
        val vault = Vault(ServerConfig(dataDir = data, machineKeyFile = machineKey))
        val error = runCatching { vault.setup("short") }.exceptionOrNull() as ApiException
        assertEquals("weak_password", error.code)
        assertEquals(ServerState.SETUP, vault.state())
    }

    @Test
    fun anExistingPlaintextDatabaseIsEncryptedInPlaceAndItsPlaintextCopiesRemoved() = withDirs { data, machineKey ->
        newMachineKey(machineKey)
        data.mkdirs()
        val config = ServerConfig(dataDir = data, machineKeyFile = machineKey)
        DriverManager.getConnection("jdbc:sqlite:${config.dbFile}").use { db ->
            db.createStatement().use {
                it.execute("PRAGMA journal_mode=WAL")
                it.execute("CREATE TABLE users (id TEXT, username TEXT)")
                it.execute("INSERT INTO users VALUES ('u1', 'alice-the-admin'), ('u2', 'bob')")
            }
        }
        config.snapshotsDir.mkdirs()
        config.dbFile.copyTo(File(config.snapshotsDir, "pre-update-abc.db"))
        assertTrue(Vault.isPlaintextSqlite(config.dbFile))

        val key = Vault(config).setup(password)
        assertFalse(Vault.isPlaintextSqlite(config.dbFile))
        assertFalse(String(config.dbFile.readBytes(), Charsets.ISO_8859_1).contains("alice-the-admin"))
        assertFalse("plaintext snapshots are deleted", File(config.snapshotsDir, "pre-update-abc.db").exists())
        Vault.openEncrypted(config.dbFile, key).use { db ->
            db.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM users").use { it.next(); assertEquals(2, it.getInt(1)) } }
        }
        // Without the key the file is noise.
        val opened = runCatching {
            DriverManager.getConnection("jdbc:sqlite:${config.dbFile}").use { db -> db.createStatement().use { it.executeQuery("SELECT * FROM users").use { r -> r.next() } } }
        }
        assertTrue(opened.isFailure)
    }

    @Test
    fun theRunningServerStoresNoReadableMetadata() = serverTest {
        members("alicemetadata", "bobmetadata")
        services.jobs.snapshot(File(services.config.snapshotsDir, "hush-test.db"))
        for (file in listOf(services.config.dbFile, File(services.config.snapshotsDir, "hush-test.db"))) {
            assertFalse("$file is encrypted", Vault.isPlaintextSqlite(file))
            assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("alicemetadata"))
        }
    }

    @Test
    fun setupAndUnlockOnlyWorkFromTheServerItselfAndTheRestOfTheApiWaits() = withDirs { data, machineKey ->
        newMachineKey(machineKey)
        val config = ServerConfig(dataDir = data, machineKeyFile = machineKey, version = "test")
        val vault = Vault(config)
        var unlocked: ByteArray? = null
        testApplication {
            application { setupServer(config, vault) { unlocked = it } }
            val http = createClient { install(ClientNegotiation) { json(Wire.json) } }
            assertEquals(ServerState.SETUP, http.get("/healthz").body<Health>().state)
            assertEquals(ServerState.SETUP, http.get("/api/v1/setup").body<SetupStatus>().state)
            val waiting = http.get("/api/v1/sync")
            assertEquals(HttpStatusCode.ServiceUnavailable, waiting.status)
            assertEquals(ServerState.SETUP, waiting.body<ApiError>().error)

            suspend fun setup(code: String, viaCloudflare: Boolean = false) = http.post("/api/v1/setup") {
                contentType(ContentType.Application.Json)
                if (viaCloudflare) header("CF-Connecting-IP", "203.0.113.9")
                setBody(SetupRequest(code, password))
            }
            val code = config.setupCodeFile.readText().trim()
            assertEquals(HttpStatusCode.Forbidden, setup(code, viaCloudflare = true).status)
            assertEquals(HttpStatusCode.Forbidden, setup("WRONG-CODE").status)
            assertNull(unlocked)
            assertEquals(HttpStatusCode.OK, setup(code).status)
            assertNotNull(unlocked)
        }

        // Later, on a machine that can't unseal the key: only the password, and only locally.
        machineKey.delete()
        val locked = Vault(config)
        assertEquals(ServerState.LOCKED, locked.state())
        var again: ByteArray? = null
        testApplication {
            application { setupServer(config, locked) { again = it } }
            val http = createClient { install(ClientNegotiation) { json(Wire.json) } }
            assertEquals(ServerState.LOCKED, http.get("/api/v1/setup").body<SetupStatus>().state)
            val viaTunnel = http.post("/api/v1/setup/unlock") {
                contentType(ContentType.Application.Json)
                header("CF-Ray", "8a1b2c3d4e5f-LHR")
                setBody(UnlockRequest(password))
            }
            assertEquals(HttpStatusCode.Forbidden, viaTunnel.status)
            val wrong = http.post("/api/v1/setup/unlock") {
                contentType(ContentType.Application.Json)
                setBody(UnlockRequest("not it, not even close"))
            }
            assertEquals(HttpStatusCode.Forbidden, wrong.status)
            Thread.sleep(2_100)
            val right = http.post("/api/v1/setup/unlock") {
                contentType(ContentType.Application.Json)
                setBody(UnlockRequest(password))
            }
            assertEquals(HttpStatusCode.OK, right.status)
            assertArrayEquals(unlocked, again)
        }
    }

    @Test
    fun logsNeverCarryIds() {
        assertEquals("/api/v1/threads/:id/read", redactIds("/api/v1/threads/AVWHXzLN578GFaWHf4ixlQ/read"))
        assertEquals("/api/v1/sync", redactIds("/api/v1/sync"))
    }
}
