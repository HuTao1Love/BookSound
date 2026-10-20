package com.zyagodin.booksound.server.db

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/**
 * The server's SQLite database. One connection, used by one thread at a time: a home server has a
 * handful of clients, and SQLite takes one writer anyway. Calls block; callers run on Dispatchers.IO.
 */
class Database(file: File) : AutoCloseable {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").apply {
        createStatement().use { s ->
            s.execute("PRAGMA journal_mode=WAL")
            s.execute("PRAGMA foreign_keys=ON")
            s.execute("PRAGMA busy_timeout=5000")
            s.execute("PRAGMA synchronous=NORMAL")
        }
    }
    private val lock = Any()
    private var inTransaction = false

    init {
        migrate()
    }

    /** Runs [block] in a transaction; nested calls join the outer one. */
    fun <T> tx(block: Tx.() -> T): T = synchronized(lock) {
        if (inTransaction) return Tx(connection).block()
        inTransaction = true
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
            inTransaction = false
        }
    }

    private fun migrate() = tx {
        exec("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)")
        var version = queryOne("SELECT version FROM schema_version") { it.getInt(1) } ?: run {
            exec("INSERT INTO schema_version (version) VALUES (0)")
            0
        }
        while (version < Schema.migrations.size) {
            Schema.migrations[version].split(";").map(String::trim).filter(String::isNotEmpty).forEach { exec(it) }
            version++
            update("UPDATE schema_version SET version = ?", version)
        }
    }

    override fun close() = connection.close()
}

class Tx(private val connection: Connection) {

    fun exec(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    fun update(sql: String, vararg args: Any?): Int = prepare(sql, args).use { it.executeUpdate() }

    fun <T> query(sql: String, vararg args: Any?, row: (ResultSet) -> T): List<T> = prepare(sql, args).use { st ->
        st.executeQuery().use { rs ->
            buildList { while (rs.next()) add(row(rs)) }
        }
    }

    fun <T> queryOne(sql: String, vararg args: Any?, row: (ResultSet) -> T): T? = query(sql, *args, row = row).firstOrNull()

    /**
     * Next number of the change feed. Every synchronized row stores the number of its latest change;
     * a client's cursor is the highest number it has seen.
     */
    fun nextSeq(): Long = queryOne("UPDATE change_seq SET value = value + 1 RETURNING value") { it.getLong(1) }!!

    private fun prepare(sql: String, args: Array<out Any?>): PreparedStatement = connection.prepareStatement(sql).apply {
        args.forEachIndexed { i, arg ->
            val index = i + 1
            when (arg) {
                null -> setNull(index, Types.NULL)
                is String -> setString(index, arg)
                is Int -> setInt(index, arg)
                is Long -> setLong(index, arg)
                is Float -> setDouble(index, arg.toDouble())
                is Double -> setDouble(index, arg)
                is Boolean -> setInt(index, if (arg) 1 else 0)
                is ByteArray -> setBytes(index, arg)
                is Enum<*> -> setString(index, arg.name)
                else -> error("Unsupported parameter ${arg::class}")
            }
        }
    }
}

fun ResultSet.str(column: String): String? = getString(column)
fun ResultSet.long(column: String): Long? = getLong(column).takeIf { !wasNull() }
fun ResultSet.int(column: String): Int? = getInt(column).takeIf { !wasNull() }
fun ResultSet.bool(column: String): Boolean = getInt(column) != 0
