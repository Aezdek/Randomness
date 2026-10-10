package xyz.aezdek.aezprint.data.store

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class JsonSerializer<T>(
    override val defaultValue: T,
    private val serializer: KSerializer<T>,
    private val json: Json,
) : Serializer<T> {

    override suspend fun readFrom(input: InputStream): T = try {
        json.decodeFromString(serializer, input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("Unreadable ${serializer.descriptor.serialName}", e)
    }

    override suspend fun writeTo(t: T, output: OutputStream) {
        output.write(json.encodeToString(serializer, t).encodeToByteArray())
    }
}

fun <T> Context.jsonDataStore(
    name: String,
    default: T,
    serializer: KSerializer<T>,
    json: Json,
    scope: CoroutineScope,
): DataStore<T> = DataStoreFactory.create(
    serializer = JsonSerializer(default, serializer, json),
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { default },
    scope = scope,
    produceFile = { File(filesDir, "datastore/$name.json") },
)
