package xyz.aezdek.aezprint.data.store

import kotlinx.serialization.Serializable
import xyz.aezdek.aezprint.domain.model.AppSettings
import xyz.aezdek.aezprint.domain.model.FakeServerConfig
import xyz.aezdek.aezprint.domain.model.LocalDocument
import xyz.aezdek.aezprint.domain.model.Preset
import xyz.aezdek.aezprint.domain.model.PrintRecord

@Serializable
data class StoredSettings(
    val app: AppSettings = AppSettings(),
    val fake: FakeServerConfig = FakeServerConfig(),
)

@Serializable
data class StoredPresets(
    val presets: List<Preset> = emptyList(),
    val defaultPresetId: String? = null,
)

@Serializable
data class StoredDocuments(val documents: List<LocalDocument> = emptyList())

@Serializable
data class StoredRecords(val records: List<PrintRecord> = emptyList())

/** Credentials are stored only as AES-GCM ciphertext under a Keystore key; see [xyz.aezdek.aezprint.data.security.KeystoreCipher]. */
@Serializable
data class StoredSecret(val ciphertext: String? = null)
