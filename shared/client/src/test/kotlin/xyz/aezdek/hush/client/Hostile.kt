package xyz.aezdek.hush.client

import xyz.aezdek.hush.crypto.B64
import xyz.aezdek.hush.crypto.ObjectCrypto
import xyz.aezdek.hush.crypto.ObjectHeader
import xyz.aezdek.hush.protocol.Audience
import xyz.aezdek.hush.protocol.Kind
import xyz.aezdek.hush.protocol.ObjectDto
import xyz.aezdek.hush.protocol.ObjectUpload

/**
 * A member with a modified app: sends messages no honest app would, properly encrypted and signed so the server takes
 * them and the other apps open them. Used against the Android app on CI's emulator (see [SmokeSeed]) and against the
 * web app by hand: whoever receives these must drop or tame every one and carry on.
 */
internal object Hostile {
    private val blob = "a".repeat(64)
    private fun media(extra: String = "") = """{"blobId":"$blob","key":"AAAA","mime":"image/jpeg","size":1$extra}"""

    /** Payloads of the wrong shape: an app must refuse them as it decodes. */
    val malformed: List<String> = listOf(
        "null", "5", "\"just text\"", "[]", "[{\"type\":\"text\",\"text\":\"in a list\"}]", "{}",
        """{"type":5}""",
        """{"type":"text","text":{"a":1}}""",
        """{"type":"text","text":["a","b"]}""",
        """{"type":"media","media":"x"}""",
        """{"type":"media","media":[null]}""",
        """{"type":"poll","poll":{"question":"q","options":"ab"}}""",
        """{"type":"poll","poll":5}""",
        """{"type":"plan","plan":{"title":{"x":1},"at":1}}""",
        """{"type":"plan","plan":"dinner"}""",
        """{"type":"location","location":{"lat":"north","lng":2,"until":3,"updatedAt":1}}""",
        """{"type":"location","location":[1,2]}""",
        """{"type":"system","event":"theme"}""",
        """{"type":"call","call":{"callId":"c","video":"yes","durationMs":1,"missed":false}}""",
        // Nested far deeper than any decoder's stack: a poster inside a poster, fifteen thousand times (it still fits
        // in the 256 KB a payload may be).
        """{"type":"media","media":[""" + """{"poster":""".repeat(15_000) + "null" + "}".repeat(15_000) + "]}",
    )

    /** Well-formed payloads carrying values no honest app sends: an app must show them tamely or not at all. */
    fun extreme(now: Long): List<String> = listOf(
        """{"type":"plan","text":"far future","plan":{"title":"The end of time","at":${Long.MAX_VALUE},"place":"${"x".repeat(5_000)}"}}""",
        """{"type":"plan","text":"before time","plan":{"title":"${"Long title ".repeat(800)}","at":${Long.MIN_VALUE}}}""",
        """{"type":"plan","text":"","plan":{"title":"","at":0}}""",
        """{"type":"location","location":{"lat":1e308,"lng":-1e308,"accuracy":-5,"until":${Long.MAX_VALUE},"updatedAt":${Long.MIN_VALUE}}}""",
        """{"type":"location","location":{"lat":91.5,"lng":181.5,"until":${now + 3_600_000},"updatedAt":$now}}""",
        """{"type":"location","location":{"lat":0,"lng":0,"accuracy":3.4e38,"until":${now + 3_600_000},"updatedAt":${now + 9_000_000_000_000}}}""",
        """{"type":"poll","text":"none","poll":{"question":"No options?","options":[]}}""",
        """{"type":"poll","text":"many","poll":{"question":"${"Why ".repeat(2_000)}","options":[${(1..1_500).joinToString(",") { "\"option $it\"" }}]}}""",
        """{"type":"poll","text":"blank","poll":{"question":"","options":["",""]}}""",
        """{"type":"media","media":[${media(""","mime":"text/html","width":-1,"height":2147483647,"blurhash":"${"#".repeat(4_000)}"""")}]}""",
        """{"type":"media","media":[${(1..300).joinToString(",") { media() }}]}""",
        """{"type":"voice","media":[${media(""","mime":"audio/mp4","durationMs":-9223372036854775808""")}]}""",
        """{"type":"voice","media":[]}""",
        """{"type":"call","call":{"callId":"c","video":true,"durationMs":${Long.MAX_VALUE},"missed":false}}""",
        """{"type":"system","event":{"theme":"${"t".repeat(3_000)}"}}""",
        """{"type":"system","event":{"nicknameFor":"nobody","nickname":"${"N".repeat(3_000)}"}}""",
        """{"type":"system","event":{"disappearAfter":${Long.MAX_VALUE}}}""",
        """{"type":"system","event":{"disappearAfter":-1}}""",
        """{"type":"system","event":{"pin":"no-such-message","quickReaction":"${"🔥".repeat(500)}"}}""",
        """{"type":"system","event":{"disappearAfter":0}}""",
        """{"type":"system","event":{"theme":"default"}}""",
        """{"type":"hologram","text":"a kind of message from the future"}""",
        """{"type":"share","sharedObjectId":"no-such-post","sharedAuthorId":"nobody"}""",
        """{"type":"story_reply","storyId":"no-such-story","text":"about a story that isn't there"}""",
        """{"type":"text","text":"reply to nothing","replyTo":"no-such-message"}""",
        """{"type":"text","text":"${"‮".repeat(50)}reversed${"\\n".repeat(400)}tall"}""",
        """{"type":"text","text":"${"W".repeat(200_000)}"}""",
        """{"type":"heart","text":"${"❤️".repeat(2_000)}"}""",
        // Files, lists, the day's question, countdowns, nudges and arrivals.
        """{"type":"file","file":{"name":"../../etc/passwd","blobId":"../../etc/passwd","key":"AAAA","size":1}}""",
        """{"type":"file","file":{"name":"${"n".repeat(5_000)}\u202Egpj.exe","blobId":"$blob","key":"AAAA","size":${Long.MAX_VALUE}}}""",
        """{"type":"file","file":{"name":"","blobId":"$blob","key":"AAAA","size":-1}}""",
        """{"type":"file"}""",
        """{"type":"list","text":"big","list":{"title":"${"T".repeat(5_000)}","items":[${(1..1_500).joinToString(",") { "\"item $it ${"x".repeat(300)}\"" }}]}}""",
        """{"type":"list","list":{"title":""}}""",
        """{"type":"list_change","replyTo":"no-such-list","listChange":{"add":"${"y".repeat(50_000)}","done":"0","undone":"0","remove":"0"}}""",
        """{"type":"list_change"}""",
        """{"type":"daily","daily":{"day":"the day after never","question":"${"Q".repeat(5_000)}","answer":"${"A".repeat(50_000)}"}}""",
        """{"type":"daily","daily":{"day":"9999-99-99","question":"","answer":""}}""",
        """{"type":"system","event":{"countdownTitle":"${"C".repeat(3_000)}","countdownAt":${Long.MAX_VALUE}}}""",
        """{"type":"system","event":{"countdownTitle":"Trip","countdownAt":${Long.MIN_VALUE}}}""",
        """{"type":"arrived","text":"${"somewhere ".repeat(2_000)}"}""",
    ) + List(40) { """{"type":"nudge"}""" }

    /** Sends [json] as the payload of a message in [threadId], exactly as given. Returns whether the server took it. */
    suspend fun send(client: AezClient, threadId: String, json: String, expiresAt: Long? = null): Boolean {
        val state = client.state.value
        val me = state.me ?: return false
        val keys = state.keys ?: return false
        val epoch = Index.build(state).epochs[threadId]?.lastOrNull() ?: return false
        val id = Composer.newObjectId()
        val now = System.currentTimeMillis()
        val header = ObjectHeader(id, Kind.MESSAGE, me.id, epoch.id, threadId, Audience.THREAD, now, expiresAt, 1, emptyList(), emptyList())
        val sealed = ObjectCrypto.seal(header, json.encodeToByteArray(), ObjectCrypto.childKey(epoch.key, id), keys)
        val upload = ObjectUpload(
            id = id, kind = Kind.MESSAGE, parentId = epoch.id, threadId = threadId, audience = Audience.THREAD, createdAt = now, expiresAt = expiresAt,
            version = 1, blobIds = emptyList(), refIds = emptyList(), payload = B64.encode(sealed.payload), commitment = B64.encode(sealed.commitment),
            signature = B64.encode(sealed.signature), wraps = emptyList(),
        )
        return runCatching { client.api.post<ObjectDto, ObjectUpload>("/objects", upload) }
            .onFailure { println("hostile: the server refused ${json.take(60)}… (${json.length} chars): ${it.message}") }
            .isSuccess
    }

    /** Everything above, into one chat. Returns how many the server accepted. */
    suspend fun unleash(client: AezClient, threadId: String): Int {
        var sent = 0
        for (json in malformed + extreme(System.currentTimeMillis())) if (send(client, threadId, json)) sent++
        return sent
    }
}
