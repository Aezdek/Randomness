package xyz.aezdek.aezgram.protocol

import kotlinx.serialization.Serializable

/**
 * The plaintext inside object envelopes, by kind. The server never sees any of this. Mirrored by
 * web/src/model/payloads.ts; unknown fields are ignored so either client can add optional fields.
 */

/** A media file: an AGM1 blob plus the key to open it. Lives only inside encrypted payloads. */
@Serializable
data class MediaRef(
    val blobId: String,
    val key: String,
    val mime: String,
    val size: Long,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long? = null,
    val blurhash: String? = null,
    val poster: MediaRef? = null,
    val alt: String? = null,
)

@Serializable
data class ProfilePayload(
    val displayName: String = "",
    val bio: String = "",
    val link: String = "",
    val pronouns: String = "",
    val avatar: MediaRef? = null,
)

@Serializable
data class PostPayload(
    val caption: String = "",
    val media: List<MediaRef>,
    val location: String? = null,
    val taggedUserIds: List<String> = emptyList(),
    val hideLikes: Boolean = false,
    val commentsOff: Boolean = false,
)

@Serializable
data class ReelPayload(
    val caption: String = "",
    val video: MediaRef,
    val cover: MediaRef? = null,
    val taggedUserIds: List<String> = emptyList(),
    val hideLikes: Boolean = false,
    val commentsOff: Boolean = false,
)

@Serializable
data class StoryText(val text: String, val background: String = "sunset", val font: String = "modern", val align: String = "center")

/** Positions are fractions of the story canvas, so stickers land in the same place on every screen size. */
@Serializable
data class Sticker(
    val id: String,
    val type: String,
    val x: Float,
    val y: Float,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val text: String? = null,
    val userId: String? = null,
    val options: List<String> = emptyList(),
    val correct: Int? = null,
    val emoji: String? = null,
    val endsAt: Long? = null,
    val url: String? = null,
    val color: String? = null,
)

object StickerType {
    const val TEXT = "text"
    const val EMOJI = "emoji"
    const val MENTION = "mention"
    const val HASHTAG = "hashtag"
    const val LOCATION = "location"
    const val LINK = "link"
    const val POLL = "poll"
    const val QUIZ = "quiz"
    const val QUESTION = "question"
    const val SLIDER = "slider"
    const val COUNTDOWN = "countdown"
    const val DRAWING = "drawing"
}

@Serializable
data class StoryPayload(
    val media: MediaRef? = null,
    val text: StoryText? = null,
    val stickers: List<Sticker> = emptyList(),
    /** A drawing layer, as a transparent PNG blob. */
    val drawing: MediaRef? = null,
    val durationMs: Long = 5_000,
)

@Serializable
data class HighlightStory(val id: String, val key: String)

@Serializable
data class HighlightPayload(val title: String, val cover: MediaRef? = null, val stories: List<HighlightStory>)

@Serializable
data class NotePayload(val text: String, val emoji: String? = null)

@Serializable
data class CommentPayload(val text: String)

@Serializable
data class LikePayload(val emoji: String = "❤️")

/** A vote on a poll, quiz or slider sticker; a child of the story, so every viewer can see the tally. */
@Serializable
data class VotePayload(val stickerId: String, val option: Int? = null, val value: Float? = null)

/** A reply to a question sticker; sealed only to the story's author. */
@Serializable
data class AnswerPayload(val storyId: String, val stickerId: String, val text: String)

@Serializable
data class CollectionPayload(val name: String, val postIds: List<String>, val isDefault: Boolean = false)

@Serializable
data class SettingsPayload(
    val closeFriends: List<String> = emptyList(),
    val mutedPosts: List<String> = emptyList(),
    val mutedStories: List<String> = emptyList(),
    val pinnedThreads: List<String> = emptyList(),
    val mutedThreads: List<String> = emptyList(),
    val verifiedKeys: Map<String, String> = emptyMap(),
    val notifyLikes: Boolean = true,
    val notifyComments: Boolean = true,
    val notifyMessages: Boolean = true,
    val notifyFollows: Boolean = true,
    val notifyStories: Boolean = true,
)

@Serializable
data class ActivityPayload(val type: String, val objectId: String, val text: String? = null)

object ActivityType {
    const val MENTION = "mention"
    const val TAG = "tag"
}

@Serializable
data class ThreadPayload(val name: String? = null, val avatar: MediaRef? = null, val theme: String? = null)

@Serializable
data class EpochPayload(val memberIds: List<String>)

@Serializable
data class CallSummary(val callId: String, val video: Boolean, val durationMs: Long, val missed: Boolean, val participants: List<String> = emptyList())

@Serializable
data class MessagePayload(
    val type: String,
    val text: String? = null,
    val media: List<MediaRef> = emptyList(),
    val replyTo: String? = null,
    val sharedObjectId: String? = null,
    val sharedAuthorId: String? = null,
    val storyId: String? = null,
    val call: CallSummary? = null,
    val edited: Boolean = false,
)

object MessageType {
    const val TEXT = "text"
    const val MEDIA = "media"
    const val VOICE = "voice"
    const val SHARE = "share"
    const val STORY_REPLY = "story_reply"
    const val STORY_REACTION = "story_reaction"
    const val CALL = "call"
    const val HEART = "heart"
    const val SYSTEM = "system"
}

@Serializable
data class ReactionPayload(val emoji: String)

@Serializable
data class LivePayload(val title: String = "", val startedAt: Long, val callId: String? = null)

/** Call/live signalling, sealed with SignalCrypto and relayed over the WebSocket. */
@Serializable
data class CallSignal(
    val type: String,
    val callId: String,
    val threadId: String? = null,
    val video: Boolean = true,
    val sdp: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val participants: List<String> = emptyList(),
    val text: String? = null,
    val live: Boolean = false,
)

object CallSignalType {
    const val INVITE = "invite"
    const val OFFER = "offer"
    const val ANSWER = "answer"
    const val ICE = "ice"
    const val ACCEPT = "accept"
    const val DECLINE = "decline"
    const val BUSY = "busy"
    const val HANGUP = "hangup"
    const val JOIN = "join"
    const val STATE = "state"
    const val CHAT = "chat"
    const val HEART = "heart"
}
