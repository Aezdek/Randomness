package xyz.aezdek.aezprint.data.store

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import xyz.aezdek.aezprint.domain.model.Preset
import xyz.aezdek.aezprint.domain.model.PresetLibrary
import xyz.aezdek.aezprint.domain.model.PrintOptions
import xyz.aezdek.aezprint.domain.repository.PresetRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PresetRepositoryImpl @Inject constructor(
    private val store: DataStore<StoredPresets>,
) : PresetRepository {

    override val library: Flow<PresetLibrary> = store.data.map { PresetLibrary(it.presets, it.defaultPresetId) }

    override suspend fun save(name: String, options: PrintOptions): Preset {
        val trimmed = name.trim()
        var saved: Preset? = null
        store.updateData { state ->
            val existing = state.presets.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
            val preset = Preset(existing?.id ?: UUID.randomUUID().toString(), trimmed, options)
            saved = preset
            val others = state.presets.filterNot { it.id == preset.id }
            state.copy(presets = (others + preset).sortedBy { it.name.lowercase() })
        }
        return checkNotNull(saved)
    }

    override suspend fun delete(id: String) {
        store.updateData { state ->
            state.copy(
                presets = state.presets.filterNot { it.id == id },
                defaultPresetId = state.defaultPresetId.takeUnless { it == id },
            )
        }
    }

    override suspend fun setDefault(id: String?) {
        store.updateData { it.copy(defaultPresetId = id) }
    }
}
