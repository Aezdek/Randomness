package xyz.aezdek.aria.core

/** Wearable Data Layer paths shared by the phone and watch apps. */
object WearPaths {
    /** DataItem: {server, key} so the watch never needs a URL typed on it. */
    const val CONFIG = "/aria/config"
    /** DataItems /aria/push/<videoId>: track JSON plus the audio (and artwork) as Assets. */
    const val PUSH_PREFIX = "/aria/push/"
    const val PUSH_TRACK = "track"
    const val PUSH_AUDIO = "audio"
    const val PUSH_ARTWORK = "artwork"
    /** Message from the watch: "the song arrived", payload = video id. */
    const val PUSH_RECEIVED = "/aria/received"
    /** Capability the watch app advertises, so the phone can find it. */
    const val WATCH_CAPABILITY = "aria_watch"
}
