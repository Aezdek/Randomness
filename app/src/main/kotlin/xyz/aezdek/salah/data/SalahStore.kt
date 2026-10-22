package xyz.aezdek.salah.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import xyz.aezdek.salah.core.SalahData
import xyz.aezdek.salah.core.SalahJson
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.DateTimeException

interface SalahStore {
    val data: Flow<SalahData>
    suspend fun update(transform: (SalahData) -> SalahData)
}

/** Keeps [SalahData] in one JSON file. DataStore writes it atomically, so a crash mid-save can't lose the log. */
class DataStoreSalahStore(context: Context, scope: CoroutineScope) : SalahStore {
    private val file = File(context.filesDir, "datastore/salah.json")

    private val store: DataStore<SalahData> = DataStoreFactory.create(
        serializer = SalahSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler {
            // Keep the unreadable file beside the new one instead of throwing the log away.
            runCatching { file.copyTo(File(file.parentFile, "salah-unreadable-${System.currentTimeMillis()}.json")) }
            SalahData()
        },
        scope = scope,
        produceFile = { file },
    )

    override val data: Flow<SalahData> = store.data

    override suspend fun update(transform: (SalahData) -> SalahData) {
        store.updateData { transform(it) }
    }
}

private object SalahSerializer : Serializer<SalahData> {
    override val defaultValue = SalahData()

    override suspend fun readFrom(input: InputStream): SalahData = try {
        SalahJson.decodeFromString(SalahData.serializer(), input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("salah.json can't be read", e)
    } catch (e: DateTimeException) {
        throw CorruptionException("salah.json has a date that can't be read", e)
    }

    override suspend fun writeTo(t: SalahData, output: OutputStream) {
        output.write(SalahJson.encodeToString(SalahData.serializer(), t).encodeToByteArray())
    }
}
