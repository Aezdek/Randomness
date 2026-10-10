package xyz.aezdek.aezprint.navigation

import kotlinx.serialization.Serializable

@Serializable
data object HomeDestination

@Serializable
data class PrintDestination(val documentId: String? = null, val uri: String? = null)

@Serializable
data class JobDestination(val localId: String)

@Serializable
data object HistoryDestination

@Serializable
data object SettingsDestination
