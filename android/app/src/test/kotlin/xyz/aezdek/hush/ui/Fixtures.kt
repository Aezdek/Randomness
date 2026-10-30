package xyz.aezdek.hush.ui

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.client.AezClient
import xyz.aezdek.hush.client.AppState
import xyz.aezdek.hush.client.Connection
import xyz.aezdek.hush.client.Entity
import xyz.aezdek.hush.client.MemorySecretStore
import xyz.aezdek.hush.client.Pin
import xyz.aezdek.hush.data.SqliteTables
import xyz.aezdek.hush.protocol.Audience
import xyz.aezdek.hush.protocol.CommentPayload
import xyz.aezdek.hush.protocol.DeviceInfo
import xyz.aezdek.hush.protocol.FollowState
import xyz.aezdek.hush.protocol.HighlightPayload
import xyz.aezdek.hush.protocol.Kind
import xyz.aezdek.hush.protocol.LikePayload
import xyz.aezdek.hush.protocol.Me
import xyz.aezdek.hush.protocol.MediaRef
import xyz.aezdek.hush.protocol.MessagePayload
import xyz.aezdek.hush.protocol.MessageType
import xyz.aezdek.hush.protocol.NotePayload
import xyz.aezdek.hush.protocol.ObjectDto
import xyz.aezdek.hush.protocol.PostPayload
import xyz.aezdek.hush.protocol.ProfilePayload
import xyz.aezdek.hush.protocol.ReactionPayload
import xyz.aezdek.hush.protocol.ReelPayload
import xyz.aezdek.hush.protocol.SettingsPayload
import xyz.aezdek.hush.protocol.StoryPayload
import xyz.aezdek.hush.protocol.StoryText
import xyz.aezdek.hush.protocol.ThreadDto
import xyz.aezdek.hush.protocol.ThreadMemberDto
import xyz.aezdek.hush.protocol.ThreadPayload
import xyz.aezdek.hush.protocol.UserDto

/**
 * A signed-in account with realistic content, already "decrypted", for screenshot tests. Media has blurhashes but no
 * blobs (the server is unreachable), so pictures render as their blurred placeholders.
 */
object Fixtures {
    const val ME = "me"
    const val MAYA = "maya"
    const val LEO = "leo"
    const val ZARA = "zara"
    const val DM = "thread-maya"
    const val GROUP = "thread-trip"

    private val now = System.currentTimeMillis()
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private var seq = 0
    private val entities = LinkedHashMap<String, Entity>()

    private val hashes = listOf(
        "LEHV6nWB2yk8pyo0adR*.7kCMdnj", "LKO2?U%2Tw=w]~RBVZRi};RPxuwH", "L6PZfSi_.AyE_3t7t7R**0o#DgR4",
        "LGF5]+Yk^6#M@-5c,1J5@[or[Q6.", "L9AB*A%LPqys8_H=yDR5nMMeVXR5", "LZG6p1{I^6rX}G=_jZSOX7xFR:WB",
    )

    private fun ref(i: Int, w: Int = 1080, h: Int = 1350, video: Boolean = false) = MediaRef(
        blobId = "blob-${seq++}", key = "", mime = if (video) "video/mp4" else "image/jpeg", size = 1, width = w, height = h,
        blurhash = hashes[i % hashes.size], durationMs = if (video) 14_000 else null,
        poster = if (video) MediaRef("blob-${seq++}", "", "image/jpeg", 1, w, h, blurhash = hashes[i % hashes.size]) else null,
    )

    private fun add(
        kind: String, author: String, payload: Any, ago: Long, audience: String = Audience.FOLLOWERS,
        parentId: String? = null, threadId: String? = null, expiresIn: Long? = null, archived: Boolean = false,
    ): Entity {
        val at = now - ago
        val dto = ObjectDto(
            id = "obj-${seq++}", kind = kind, authorId = author, parentId = parentId, threadId = threadId, audience = audience,
            createdAt = at, expiresAt = expiresIn?.let { now + it }, version = 1, payload = "", commitment = "", signature = "",
            archived = archived, receivedAt = at,
        )
        return Entity(dto, ByteArray(32), payload).also { entities[it.id] = it }
    }

    private fun user(id: String, name: String, online: Boolean = false, followsMe: Boolean = true) = UserDto(
        id = id, username = name, signPk = "", boxPk = "", boxPkSig = "", keyVersion = 1, isPrivate = false, createdAt = now - 90L * 24 * HOUR,
        followState = FollowState.FOLLOWING, followsMe = if (followsMe) FollowState.FOLLOWING else FollowState.NONE, blockedByMe = false,
        lastActiveAt = if (online) now else now - 3 * HOUR, online = online,
    )

    fun state(): AppState {
        entities.clear()
        val users = mapOf(
            MAYA to user(MAYA, "maya.lens", online = true),
            LEO to user(LEO, "leo_outdoors"),
            ZARA to user(ZARA, "zara", online = true, followsMe = false),
        )
        add(Kind.PROFILE, ME, ProfilePayload("Aez", "Building things. Mostly at night.", "aezdek.xyz", avatar = ref(4, 640, 640)), 300 * HOUR, Audience.MEMBERS)
        add(Kind.PROFILE, MAYA, ProfilePayload("Maya", "Film and light ☀️", avatar = ref(1, 640, 640)), 300 * HOUR, Audience.MEMBERS)
        add(Kind.PROFILE, LEO, ProfilePayload("Leo", "Mountains > everything", avatar = ref(2, 640, 640)), 300 * HOUR, Audience.MEMBERS)
        add(Kind.PROFILE, ZARA, ProfilePayload("Zara", avatar = ref(5, 640, 640)), 300 * HOUR, Audience.MEMBERS)

        val sunset = add(Kind.POST, MAYA, PostPayload("Golden hour from the roof. No filter, promise #sunset #film", listOf(ref(0), ref(3)), location = "Lisbon"), 2 * HOUR)
        add(Kind.LIKE, LEO, LikePayload(), 90 * MIN, parentId = sunset.id)
        add(Kind.LIKE, ZARA, LikePayload(), 80 * MIN, parentId = sunset.id)
        add(Kind.LIKE, ME, LikePayload(), 70 * MIN, parentId = sunset.id)
        add(Kind.COMMENT, LEO, CommentPayload("This light is unreal"), 60 * MIN, parentId = sunset.id)
        add(Kind.COMMENT, ME, CommentPayload("@maya.lens print this one"), 40 * MIN, parentId = sunset.id)

        val ridge = add(Kind.POST, LEO, PostPayload("Made it up before the clouds rolled in", listOf(ref(2, 1080, 1080)), location = "Tatra Mountains"), 20 * HOUR)
        add(Kind.LIKE, MAYA, LikePayload(), 19 * HOUR, parentId = ridge.id)
        add(Kind.POST, ME, PostPayload("New desk, same chaos", listOf(ref(4))), 50 * HOUR)
        add(Kind.POST, ME, PostPayload("Sunday", listOf(ref(1, 1080, 1080))), 80 * HOUR)
        add(Kind.REEL, ME, ReelPayload("Night drive", ref(5, 1080, 1920, video = true)), 100 * HOUR)
        add(Kind.POST, ME, PostPayload("Coffee number three", listOf(ref(3))), 120 * HOUR)
        add(Kind.POST, ZARA, PostPayload("Market day", listOf(ref(5))), 30 * HOUR, Audience.MEMBERS)

        add(Kind.STORY, MAYA, StoryPayload(text = StoryText("Who's up for the film festival on Friday?", "sunset", "strong")), 3 * HOUR, expiresIn = 21 * HOUR)
        add(Kind.STORY, LEO, StoryPayload(media = ref(2, 1080, 1920)), 5 * HOUR, expiresIn = 19 * HOUR)
        add(Kind.STORY, ZARA, StoryPayload(media = ref(3, 1080, 1920)), 6 * HOUR, Audience.CLOSE_FRIENDS, expiresIn = 18 * HOUR)
        add(Kind.STORY, ME, StoryPayload(text = StoryText("Shipping day 🚀", "ocean", "modern")), 30 * HOUR, expiresIn = -6 * HOUR)
        add(Kind.HIGHLIGHT, ME, HighlightPayload("Travel", ref(2, 640, 640), emptyList()), 200 * HOUR)
        add(Kind.HIGHLIGHT, ME, HighlightPayload("Food", ref(3, 640, 640), emptyList()), 210 * HOUR)

        add(Kind.NOTE, MAYA, NotePayload("coffee later?", "☕"), 1 * HOUR, expiresIn = 23 * HOUR)
        add(Kind.NOTE, LEO, NotePayload("back from the hills", "🏔️"), 4 * HOUR, expiresIn = 20 * HOUR)

        add(Kind.SETTINGS, ME, SettingsPayload(closeFriends = listOf(MAYA), pinnedThreads = listOf(DM)), 400 * HOUR, Audience.SELF)

        fun message(thread: String, author: String, text: String, ago: Long, type: String = MessageType.TEXT) =
            add(Kind.MESSAGE, author, MessagePayload(type, text = text), ago, Audience.THREAD, threadId = thread)
        message(DM, MAYA, "are you coming tonight?", 50 * MIN)
        message(DM, ME, "yes! leaving in 20", 45 * MIN)
        val heart = message(DM, MAYA, "bring the polaroid 📸", 44 * MIN)
        add(Kind.REACTION, ME, ReactionPayload("❤️"), 43 * MIN, Audience.THREAD, parentId = heart.id, threadId = DM)
        message(DM, ME, "already in my bag", 42 * MIN)
        message(DM, MAYA, "you're the best", 5 * MIN)
        add(Kind.THREAD, ME, ThreadPayload(name = "Weekend trip ✈️"), 72 * HOUR, Audience.THREAD, threadId = GROUP)
        message(GROUP, LEO, "Train at 8:40, don't be late", 3 * HOUR)
        message(GROUP, ZARA, "I'll bring snacks", 2 * HOUR)

        val threads = mapOf(
            DM to ThreadDto(DM, false, ME, now - 200 * HOUR, listOf(member(ME, now - 42 * MIN), member(MAYA, now - 10 * MIN))),
            GROUP to ThreadDto(GROUP, true, ME, now - 72 * HOUR, listOf(member(ME, now - 4 * HOUR), member(LEO, now), member(ZARA, now))),
        )
        val me = Me(ME, "aezdek", isAdmin = true, isPrivate = true, showActivity = true, createdAt = now - 400 * HOUR, keyVersion = 1)
        return AppState(
            me = me,
            users = users,
            threads = threads,
            entities = LinkedHashMap(entities),
            pins = mapOf(MAYA to Pin("", 1, verified = true), LEO to Pin("", 1)),
            followRequests = emptyList(),
            presence = mapOf(MAYA to true, ZARA to true),
            connection = Connection.ONLINE,
            initialSyncDone = true,
        )
    }

    private fun member(id: String, read: Long) = ThreadMemberDto(id, "member", now - 300 * HOUR, read)

    /** Points [Graph] at a client holding [state]; nothing reaches the network (the server address refuses). */
    fun install(context: Context): AezClient {
        val client = AezClient(
            "http://127.0.0.1:9", OkHttpClient(), SqliteTables(context, "shots-${System.nanoTime()}.db"), MemorySecretStore(),
            DeviceInfo("Test", "android"), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        client.state.value = state()
        Graph.initForTest(context, client)
        return client
    }
}
