package xyz.aezdek.aezgram.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One SQLite connection behind a lock. For at most ten people that is plenty, and it makes every request a simple
 * serialisable transaction.
 */
class Db(file: File) : AutoCloseable {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
    private val lock = ReentrantLock()
    private var depth = 0

    init {
        connection.createStatement().use {
            it.execute("PRAGMA journal_mode=WAL")
            it.execute("PRAGMA synchronous=NORMAL")
            it.execute("PRAGMA foreign_keys=ON")
            it.execute("PRAGMA busy_timeout=5000")
        }
        Schema.migrate(this)
    }

    /** Runs [block] in a transaction; nested calls join the outer one. */
    fun <T> tx(block: Tx.() -> T): T = lock.withLock {
        if (depth > 0) return@withLock Tx(connection).block()
        depth++
        connection.autoCommit = false
        try {
            val result = Tx(connection).block()
            connection.commit()
            result
        } catch (t: Throwable) {
            connection.rollback()
            throw t
        } finally {
            connection.autoCommit = true
            depth--
        }
    }

    suspend fun <T> io(block: Tx.() -> T): T = withContext(Dispatchers.IO) { tx(block) }

    /** Statements that cannot run inside a transaction (VACUUM INTO). */
    fun raw(sql: String) = lock.withLock { connection.createStatement().use { it.execute(sql) } }

    override fun close() = lock.withLock { connection.close() }
}

class Tx(private val connection: Connection) {

    fun exec(sql: String, vararg args: Any?): Int = prepare(sql, args).use { it.executeUpdate() }

    fun <T> query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> = prepare(sql, args).use { statement ->
        statement.executeQuery().use { rows ->
            val out = ArrayList<T>()
            while (rows.next()) out += map(rows)
            out
        }
    }

    fun <T> one(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? = query(sql, *args, map = map).firstOrNull()

    fun long(sql: String, vararg args: Any?): Long? = one(sql, *args) { it.getLong(1).takeUnless { _ -> it.wasNull() } }

    fun exists(sql: String, vararg args: Any?): Boolean = one(sql, *args) { true } ?: false

    fun strings(sql: String, vararg args: Any?): List<String> = query(sql, *args) { it.getString(1) }

    fun insertReturningId(sql: String, vararg args: Any?): Long {
        exec(sql, *args)
        return long("SELECT last_insert_rowid()")!!
    }

    private fun prepare(sql: String, args: Array<out Any?>): PreparedStatement {
        val statement = connection.prepareStatement(sql)
        args.forEachIndexed { index, value ->
            val position = index + 1
            when (value) {
                null -> statement.setNull(position, Types.NULL)
                is String -> statement.setString(position, value)
                is Int -> statement.setInt(position, value)
                is Long -> statement.setLong(position, value)
                is Boolean -> statement.setInt(position, if (value) 1 else 0)
                is ByteArray -> statement.setBytes(position, value)
                is Double -> statement.setDouble(position, value)
                else -> error("unsupported SQL parameter ${value::class}")
            }
        }
        return statement
    }
}

fun ResultSet.str(column: String): String = getString(column)
fun ResultSet.strOrNull(column: String): String? = getString(column)
fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }
fun ResultSet.bool(column: String): Boolean = getInt(column) != 0
fun ResultSet.bytes(column: String): ByteArray = getBytes(column) ?: ByteArray(0)

/** `?, ?, ?` for an IN clause. */
fun placeholders(count: Int) = List(count) { "?" }.joinToString(", ")
