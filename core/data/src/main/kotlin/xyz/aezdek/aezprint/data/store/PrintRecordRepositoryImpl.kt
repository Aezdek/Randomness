package xyz.aezdek.aezprint.data.store

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import xyz.aezdek.aezprint.domain.model.PrintRecord
import xyz.aezdek.aezprint.domain.repository.PrintRecordRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrintRecordRepositoryImpl @Inject constructor(
    private val store: DataStore<StoredRecords>,
) : PrintRecordRepository {

    override val records: Flow<List<PrintRecord>> = store.data.map { state -> state.records.sortedByDescending { it.createdAt } }

    override fun record(localId: String): Flow<PrintRecord?> =
        store.data.map { state -> state.records.firstOrNull { it.localId == localId } }.distinctUntilChanged()

    override suspend fun get(localId: String): PrintRecord? = record(localId).first()

    override suspend fun upsert(record: PrintRecord) {
        store.updateData { state ->
            val others = state.records.filterNot { it.localId == record.localId }
            state.copy(records = (others + record).sortedByDescending { it.createdAt }.take(MAX_RECORDS))
        }
    }

    override suspend fun update(localId: String, transform: (PrintRecord) -> PrintRecord) {
        store.updateData { state ->
            state.copy(records = state.records.map { if (it.localId == localId) transform(it) else it })
        }
    }

    private companion object {
        const val MAX_RECORDS = 200
    }
}
