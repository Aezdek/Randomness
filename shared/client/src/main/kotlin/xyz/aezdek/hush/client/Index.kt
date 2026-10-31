package xyz.aezdek.hush.client

import xyz.aezdek.hush.protocol.ActivityPayload
import xyz.aezdek.hush.protocol.AnswerPayload
import xyz.aezdek.hush.protocol.CollectionPayload
import xyz.aezdek.hush.protocol.FollowState
import xyz.aezdek.hush.protocol.Kind
import xyz.aezdek.hush.protocol.MessagePayload
import xyz.aezdek.hush.protocol.ProfilePayload
import xyz.aezdek.hush.protocol.SettingsPayload
import xyz.aezdek.hush.protocol.ThreadDto
import xyz.aezdek.hush.protocol.UserDto

/** Everything the UI looks things up by, rebuilt whenever the entity map changes (mirrors web/src/core/selectors.ts). */
class Index(
    val profiles: Map<String, Entity>,
    /** Posts and reels, newest first. */
    val posts: List<Entity>,
    /** Live stories per author, oldest first. */
    val stories: Map<String, List<Entity>>,
    val myStoryArchive: List<Entity>,
    val highlights: Map<String, List<Entity>>,
    val notes: Map<String, Entity>,
    /** Comments, likes, votes and reactions by parent, oldest first. */
    val children: Map<String, List<Entity>>,
    val messages: Map<String, List<Entity>>,
    val threadMeta: Map<String, Entity>,
    val epochs: Map<String, List<Entity>>,
    val settingsEntity: Entity?,
    val collections: List<Entity>,
    val activity: List<Entity>,
    val answers: List<Entity>,
    val lives: List<Entity>,
) {
    val settings: SettingsPayload get() = settingsEntity?.payloadAs<SettingsPayload>() ?: SettingsPayload()

    fun profile(userId: String?): ProfilePayload? = userId?.let { profiles[it]?.payloadAs<ProfilePayload>() }

    fun likesOf(parentId: String) = children[parentId].orEmpty().filter { it.dto.kind == Kind.LIKE }
    fun commentsOf(parentId: String) = children[parentId].orEmpty().filter { it.dto.kind == Kind.COMMENT }
    fun reactionsOf(parentId: String) = children[parentId].orEmpty().filter { it.dto.kind == Kind.REACTION }
    fun isLiked(parentId: String, me: String) = children[parentId].orEmpty().any { it.dto.kind == Kind.LIKE && it.dto.authorId == me }

    fun savedPostIds(): Set<String> =
        collections.flatMap { c -> c.payloadAs<CollectionPayload>()?.takeIf { it.isDefault }?.postIds.orEmpty() }.toSet()

    fun unreadCount(thread: ThreadDto, me: String): Int {
        val mine = thread.members.firstOrNull { it.userId == me } ?: return 0
        return messages[thread.id].orEmpty().count { it.dto.authorId != me && it.dto.receivedAt > mine.lastReadAt }
    }

    fun lastMessage(threadId: String): Entity? = messages[threadId]?.lastOrNull()

    private val customs = HashMap<String, ChatCustom>()

    /** The chat's theme, nicknames, quick reaction and pins (computed once per index). */
    fun chatCustom(threadId: String): ChatCustom = synchronized(customs) {
        customs.getOrPut(threadId) { ChatCustom.of(messages[threadId].orEmpty()) }
    }

    /** What a chat calls someone: their nickname there, else their display name, else their username. */
    fun nameIn(threadId: String?, userId: String, users: Map<String, UserDto>): String =
        threadId?.let { chatCustom(it).nicknames[userId] }
            ?: profile(userId)?.displayName?.takeIf { it.isNotBlank() } ?: users[userId]?.username ?: "someone"

    fun threadTitle(thread: ThreadDto, users: Map<String, UserDto>, me: String): String {
        threadMeta[thread.id]?.payloadAs<xyz.aezdek.hush.protocol.ThreadPayload>()?.name?.takeIf { it.isNotBlank() }?.let { return it }
        val others = thread.members.filter { it.userId != me }.map { member -> nameIn(thread.id, member.userId, users) }
        return others.joinToString(", ").ifEmpty { "Just you" }
    }

    /** The line a chat event shows in the conversation and the inbox: "You changed the theme to Ocean". */
    fun eventText(threadId: String?, authorId: String, event: xyz.aezdek.hush.protocol.ChatEvent, users: Map<String, UserDto>, me: String): String {
        val who = if (authorId == me) "You" else nameIn(threadId, authorId, users)
        fun whom(id: String) = if (id == me) "you" else users[id]?.username ?: "someone"
        return when {
            event.theme != null -> "$who changed the theme to ${ChatThemes.byId(event.theme)?.name ?: "Hush"}"
            event.nicknameFor != null && event.nickname.isNullOrBlank() -> "$who removed the nickname for ${whom(event.nicknameFor!!)}"
            event.nicknameFor != null -> if (event.nicknameFor == authorId) "$who set ${if (authorId == me) "your" else "their"} nickname to ${event.nickname!!.trim()}"
                else "$who set the nickname for ${whom(event.nicknameFor!!)} to ${event.nickname!!.trim()}"
            event.quickReaction != null -> "$who set the quick reaction to ${event.quickReaction}"
            event.pin != null -> "$who pinned a message"
            event.unpin != null -> "$who unpinned a message"
            else -> "$who changed the chat"
        }
    }

    fun displayName(user: UserDto?): String =
        profile(user?.id)?.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: user?.username ?: "Someone"

    companion object {
        fun build(state: AppState, now: Long = System.currentTimeMillis()): Index {
            val me = state.me?.id
            val profiles = HashMap<String, Entity>()
            val posts = ArrayList<Entity>()
            val stories = HashMap<String, MutableList<Entity>>()
            val archive = ArrayList<Entity>()
            val highlights = HashMap<String, MutableList<Entity>>()
            val notes = HashMap<String, Entity>()
            val children = HashMap<String, MutableList<Entity>>()
            val messages = HashMap<String, MutableList<Entity>>()
            val threadMeta = HashMap<String, Entity>()
            val epochs = HashMap<String, MutableList<Entity>>()
            var settings: Entity? = null
            val collections = ArrayList<Entity>()
            val activity = ArrayList<Entity>()
            val answers = ArrayList<Entity>()
            val lives = ArrayList<Entity>()
            for (entity in state.entities.values) {
                val dto = entity.dto
                val parent = dto.parentId
                if (parent != null && dto.kind != Kind.MESSAGE) children.getOrPut(parent) { ArrayList() } += entity
                when (dto.kind) {
                    Kind.PROFILE -> {
                        val current = profiles[dto.authorId]
                        if (current == null || current.dto.version < dto.version) profiles[dto.authorId] = entity
                    }
                    Kind.POST, Kind.REEL -> posts += entity
                    Kind.STORY -> {
                        if (dto.authorId == me) archive += entity
                        if ((dto.expiresAt ?: 0) > now && !dto.archived) stories.getOrPut(dto.authorId) { ArrayList() } += entity
                    }
                    Kind.HIGHLIGHT -> highlights.getOrPut(dto.authorId) { ArrayList() } += entity
                    Kind.NOTE -> if ((dto.expiresAt ?: 0) > now) {
                        val current = notes[dto.authorId]
                        if (current == null || current.dto.createdAt < dto.createdAt) notes[dto.authorId] = entity
                    }
                    Kind.MESSAGE -> dto.threadId?.let { messages.getOrPut(it) { ArrayList() } += entity }
                    Kind.THREAD -> dto.threadId?.let { threadMeta[it] = entity }
                    Kind.EPOCH -> dto.threadId?.let { epochs.getOrPut(it) { ArrayList() } += entity }
                    Kind.SETTINGS -> if (dto.authorId == me && (settings == null || settings.dto.receivedAt < dto.receivedAt)) settings = entity
                    Kind.COLLECTION -> if (dto.authorId == me) collections += entity
                    Kind.ACTIVITY -> if (dto.authorId != me) activity += entity
                    Kind.ANSWER -> answers += entity
                    Kind.LIVE -> if ((dto.expiresAt ?: 0) > now) lives += entity
                }
            }
            posts.sortByDescending { it.dto.createdAt }
            stories.values.forEach { list -> list.sortBy { it.dto.createdAt } }
            archive.sortByDescending { it.dto.createdAt }
            highlights.values.forEach { list -> list.sortBy { it.dto.createdAt } }
            children.values.forEach { list -> list.sortBy { it.dto.createdAt } }
            messages.values.forEach { list -> list.sortBy { it.dto.receivedAt } }
            epochs.values.forEach { list -> list.sortBy { it.dto.receivedAt } }
            activity.sortByDescending { it.dto.receivedAt }
            return Index(profiles, posts, stories, archive, highlights, notes, children, messages, threadMeta, epochs, settings,
                collections, activity, answers, lives)
        }

        val EMPTY = build(AppState())
    }
}

/** People whose stories the tray shows, unseen first, then by newest story. */
fun Index.storyRing(state: AppState): List<String> {
    val me = state.me?.id
    val users = state.users
    val muted = settings.mutedStories.toSet()
    return stories.keys
        .filter { it != me && it !in muted && users[it]?.blockedByMe != true }
        .sortedWith(compareBy<String> { id -> stories[id].orEmpty().all { state.seenStories.containsKey(it.id) } }
            .thenByDescending { id -> stories[id].orEmpty().maxOfOrNull { it.dto.createdAt } ?: 0 })
}

fun Index.hasUnseenStory(userId: String, state: AppState) = stories[userId].orEmpty().any { !state.seenStories.containsKey(it.id) }

fun Index.activityFor(me: String): List<Entity> = activity.filter { it.payloadAs<ActivityPayload>() != null && it.dto.authorId != me }

fun Index.answersTo(me: String): List<Entity> = answers.filter { it.dto.authorId != me && it.payloadAs<AnswerPayload>() != null }

fun Index.messagePayload(entity: Entity): MessagePayload? = entity.payloadAs<MessagePayload>()

fun UserDto.isFollowing() = followState == FollowState.FOLLOWING
fun UserDto.followsMeBack() = followsMe == FollowState.FOLLOWING
