package xyz.aezdek.hush.server

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.sqlite.mc.SQLiteMCChacha20Config
import xyz.aezdek.hush.crypto.KdfParams
import xyz.aezdek.hush.crypto.KeySealing
import xyz.aezdek.hush.crypto.PasswordKeys
import xyz.aezdek.hush.crypto.Sodium
import xyz.aezdek.hush.crypto.toHex
import xyz.aezdek.hush.protocol.ServerState
import xyz.aezdek.hush.protocol.Wire
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager

/**
 * The server's database is encrypted at rest (SQLite3MultipleCiphers, ChaCha20-Poly1305) with a random 32-byte key.
 * That key is never stored in the clear: the AezCrypt keyring (data/keyring.json) keeps it sealed twice,
 *
 * - under the admin's **server password** (Argon2id), set once with update.sh on the server itself, and
 * - under this **machine's key** (secrets/machine.key outside the data folder, or sealed to the TPM), so restarts
 *   unlock by themselves.
 *
 * Backups carry the keyring but never the machine key, so a copy of the backup (or of the data folder) is useless
 * without the server password. Until the password is set, the server stores nothing at all.
 */
class Vault(private val config: ServerConfig) {
    private val log = LoggerFactory.getLogger(Vault::class.java)
    private val lock = Any()

    fun state(): String = if (config.keyringFile.isFile) ServerState.LOCKED else ServerState.SETUP

    /** The one-time code update.sh reads to prove it runs on this machine; made when the server first waits for setup. */
    fun setupCode(): String = synchronized(lock) {
        config.setupCodeFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } ?: run {
            val code = Sodium.random(10).toHex().uppercase().chunked(5).joinToString("-")
            config.dataDir.mkdirs()
            writePrivate(config.setupCodeFile, "$code\n")
            code
        }
    }

    /** The database key, if this machine can unseal it by itself. */
    fun unlockWithMachineKey(): ByteArray? = synchronized(lock) {
        val ring = read() ?: return null
        val machine = machineKey() ?: return null
        val slot = ring.machine?.takeIf { it.keyId == keyId(machine) } ?: return null
        val key = KeySealing.open(machine, B64u.dec(slot.sealed, "keyring"), MACHINE_AD) ?: return null
        ready(key)
        key
    }

    /** First setup: a new database key, sealed under [password] (and this machine). Returns the key. */
    fun setup(password: String): ByteArray = synchronized(lock) {
        if (config.keyringFile.isFile) conflict("This server is already set up; unlock it instead", "exists")
        checkPassword(password)
        val key = Sodium.random(32)
        val salt = PasswordKeys.newSalt()
        val sealed = KeySealing.seal(KeySealing.passwordKey(password, salt, KDF), key, PASSWORD_AD)
        val ring = Keyring(
            createdAt = System.currentTimeMillis(),
            password = PasswordSlot(B64u.enc(salt), KDF.opsLimit, KDF.memLimitBytes, B64u.enc(sealed)),
            machine = machineKey()?.let { sealForMachine(key, it) },
        )
        // The keyring is on disk before anything is encrypted with its key, so a crash can never lose the key.
        write(ring)
        if (ring.machine == null) log.warn("No machine key at {}: the server password will be needed after every restart", config.machineKeyFile)
        ready(key)
        log.info("Server password set; the database is encrypted with AezCrypt-sealed keys")
        key
    }

    /** Opens the keyring with the server password; reseals it for this machine so the next restart unlocks by itself. */
    fun unlock(password: String): ByteArray? = synchronized(lock) {
        val ring = read() ?: return null
        val slot = ring.password
        val passwordKey = KeySealing.passwordKey(password, B64u.dec(slot.salt, "keyring"), KdfParams(slot.opsLimit, slot.memLimit))
        val key = KeySealing.open(passwordKey, B64u.dec(slot.sealed, "keyring"), PASSWORD_AD) ?: return null
        val machine = machineKey()
        if (machine != null && ring.machine?.keyId != keyId(machine)) {
            write(ring.copy(machine = sealForMachine(key, machine)))
            log.info("Sealed the database key for this machine; restarts unlock by themselves again")
        }
        ready(key)
        key
    }

    private fun checkPassword(password: String) {
        if (password.length < MIN_PASSWORD) badRequest("Use at least $MIN_PASSWORD characters", "weak_password")
    }

    /** Before the database opens: encrypt one left from before encryption, and remove plaintext copies of it. */
    private fun ready(key: ByteArray) {
        encryptIfPlaintext(config.dbFile, key)
        config.snapshotsDir.listFiles()?.filter { isPlaintextSqlite(it) }?.forEach {
            log.info("Removing {}: a copy from before the database was encrypted", it.name)
            it.delete()
        }
        config.setupCodeFile.delete()
    }

    /**
     * Encrypts a plaintext database in place: checkpoint, copy, encrypt the copy, check it, then swap it in. The
     * plaintext file is only replaced once the encrypted one has been read back.
     */
    private fun encryptIfPlaintext(file: File, key: ByteArray) {
        if (!isPlaintextSqlite(file)) return
        log.info("Encrypting the existing database")
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { plain ->
            plain.createStatement().use {
                it.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                it.execute("PRAGMA journal_mode=DELETE")
            }
        }
        val rows = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { countRows(it) }
        val copy = File(file.parentFile, file.name + ".aezcrypt")
        file.copyTo(copy, overwrite = true)
        DriverManager.getConnection("jdbc:sqlite:${copy.absolutePath}").use { db ->
            db.createStatement().use {
                it.execute("PRAGMA cipher='chacha20'")
                it.execute("PRAGMA hexrekey='${key.toHex()}'")
            }
        }
        openEncrypted(copy, key).use { db ->
            val check = db.createStatement().use { s -> s.executeQuery("PRAGMA integrity_check").use { it.next(); it.getString(1) } }
            check(check == "ok") { "the encrypted copy failed its integrity check: $check" }
            check(countRows(db) == rows) { "the encrypted copy lost rows" }
        }
        check(!isPlaintextSqlite(copy)) { "the copy is still plaintext" }
        Files.move(copy.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        File(file.parentFile, file.name + "-wal").delete()
        File(file.parentFile, file.name + "-shm").delete()
        log.info("Database encrypted")
    }

    private fun countRows(db: Connection): Map<String, Long> {
        val tables = db.createStatement().use { s ->
            s.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").use { r ->
                buildList { while (r.next()) add(r.getString(1)) }
            }
        }
        return tables.associateWith { t -> db.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM \"$t\"").use { it.next(); it.getLong(1) } } }
    }

    private fun sealForMachine(key: ByteArray, machine: ByteArray) = MachineSlot(keyId(machine), B64u.enc(KeySealing.seal(machine, key, MACHINE_AD)))

    /** This machine's key: 64 hex characters in a root-only file the container gets read-only (see compose.yml). */
    private fun machineKey(): ByteArray? {
        val file = config.machineKeyFile ?: return null
        if (!file.isFile) return null
        val hex = runCatching { file.readText().trim() }.getOrNull() ?: return null
        if (!hex.matches(Regex("[0-9a-fA-F]{64}"))) {
            log.warn("{} isn't a 32-byte hex key; ignoring it", file)
            return null
        }
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun keyId(machine: ByteArray) = MessageDigest.getInstance("SHA-256").digest(machine).copyOf(8).toHex()

    private fun read(): Keyring? = config.keyringFile.takeIf { it.isFile }?.let {
        val ring = Wire.json.decodeFromString(Keyring.serializer(), it.readText())
        check(ring.format == FORMAT) { "unknown keyring format ${ring.format}" }
        ring
    }

    private fun write(ring: Keyring) = writePrivate(config.keyringFile, Wire.json.encodeToString(Keyring.serializer(), ring) + "\n")

    /** Atomic, flushed to disk, readable only by the server's user. */
    private fun writePrivate(target: File, text: String) {
        target.parentFile.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.tmp")
        FileOutputStream(temp).use { out ->
            out.write(text.encodeToByteArray())
            out.fd.sync()
        }
        runCatching { Files.setPosixFilePermissions(temp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    @Serializable
    data class Keyring(val format: String = FORMAT, val createdAt: Long, val password: PasswordSlot, val machine: MachineSlot? = null)

    @Serializable
    data class PasswordSlot(val salt: String, val opsLimit: Long, val memLimit: Int, val sealed: String)

    @Serializable
    data class MachineSlot(val keyId: String, val sealed: String)

    companion object {
        const val FORMAT = "AezCrypt-keyring-1"
        const val MIN_PASSWORD = 12
        private val PASSWORD_AD = "AezCrypt keyring v1 / server password".encodeToByteArray()
        private val MACHINE_AD = "AezCrypt keyring v1 / machine".encodeToByteArray()
        /** Same cost as account passwords; unlocking is rare (setup, a restore), so a second is fine. */
        private val KDF = KdfParams.DEFAULT
        private val SQLITE_MAGIC = "SQLite format 3\u0000".encodeToByteArray()

        fun isPlaintextSqlite(file: File): Boolean {
            if (!file.isFile || file.length() < SQLITE_MAGIC.size) return false
            val head = ByteArray(SQLITE_MAGIC.size)
            file.inputStream().use { it.read(head) }
            return head.contentEquals(SQLITE_MAGIC)
        }

        /** A connection to an encrypted database. The key goes in through the driver, never in the JDBC URL. */
        fun openEncrypted(file: File, key: ByteArray): Connection =
            SQLiteMCChacha20Config.getDefault().withHexKey(key.toHex()).build().createConnection("jdbc:sqlite:${file.absolutePath}")
    }
}
