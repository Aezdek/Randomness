package xyz.aezdek.aezprint.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Preset(
    val id: String,
    val name: String,
    val options: PrintOptions,
)

data class PresetLibrary(
    val presets: List<Preset> = emptyList(),
    val defaultPresetId: String? = null,
) {
    val default: Preset? get() = presets.firstOrNull { it.id == defaultPresetId }
}
