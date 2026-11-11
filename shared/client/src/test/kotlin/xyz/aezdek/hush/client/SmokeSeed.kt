package xyz.aezdek.hush.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import xyz.aezdek.hush.crypto.KdfParams
import xyz.aezdek.hush.protocol.ChatEvent
import xyz.aezdek.hush.protocol.DeviceInfo
import xyz.aezdek.hush.protocol.MessagePayload
import xyz.aezdek.hush.protocol.MessageType
import xyz.aezdek.hush.protocol.PostPayload
import xyz.aezdek.hush.protocol.ProfilePayload
import xyz.aezdek.hush.protocol.StoryPayload
import xyz.aezdek.hush.protocol.StoryText
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * The other person in CI's Android smoke test (.github/workflows/hush.yml, android-smoke). Skipped unless
 * HUSH_SEED_URL is set. Maya signs up, posts, waits for the phone's account ("ava"), then follows it, messages it
 * (text, a photo, a reaction, a chat theme) and accepts its follow request, so every screen the phone opens has
 * something real on it. Writes the chat's id to HUSH_SEED_OUT for the script driving the phone.
 */
class SmokeSeed {
    @Test
    fun seed() = runBlocking {
        val url = System.getenv("HUSH_SEED_URL")
        assumeTrue("Only for the Android smoke test", !url.isNullOrBlank())
        val invite = System.getenv("HUSH_SEED_INVITE")!!.trim()
        val out = File(System.getenv("HUSH_SEED_OUT")!!)
        val photos = System.getenv("HUSH_SEED_PHOTOS")!!.split(':').map(::File)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        val maya = AezClient(url, http, MemoryTables(), MemorySecretStore(), DeviceInfo("smoke test", "web"), scope, KdfParams.FAST_FOR_TESTS)
        try {
            maya.signUp(invite, "maya", "maya has a long password")
            maya.composer.saveProfile(ProfilePayload(displayName = "Maya Okafor", bio = "Film photos and long walks"))
            val refs = photos.map { file ->
                val image = ImageIO.read(file)
                maya.media.upload(PreparedMedia(file, "image/jpeg", image.width, image.height, blurhash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj"))
            }
            val post = maya.composer.createPost(PostPayload(caption = "Saturday light #film", media = refs))
            maya.composer.createStory(StoryPayload(text = StoryText("Coffee later?")), closeFriends = false)
            println("seed: maya is ready")

            val ava = withTimeout(TimeUnit.MINUTES.toMillis(12)) {
                while (true) {
                    runCatching { maya.engine.sync() }
                    maya.state.value.users.values.firstOrNull { it.username == "ava" }?.let { return@withTimeout it }
                    delay(2000)
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
            println("seed: found ava")
            maya.composer.follow(ava.id)
            val thread = maya.composer.openDirect(ava.id)
            val first = maya.composer.sendMessage(thread, MessagePayload(MessageType.TEXT, text = "Hey! Is this the new app?"))
            maya.composer.sendMessage(thread, MessagePayload(MessageType.MEDIA, media = refs.take(1)))
            maya.composer.sendMessage(thread, MessagePayload(MessageType.TEXT, text = "Took this one this morning", replyTo = first.id))
            maya.composer.sendChatEvent(thread, ChatEvent(theme = "dusk"))
            maya.composer.react(first, "🔥")
            out.writeText("thread=$thread\npost=${post.id}\nmaya=${maya.state.value.me!!.id}\nava=${ava.id}\n")
            println("seed: wrote $out")

            // Accept the phone's follow request whenever it comes, and answer what it sends, for as long as the test runs.
            val answered = HashSet<String>()
            repeat(450) {
                runCatching {
                    maya.engine.sync()
                    if (ava.id in maya.state.value.followRequests) maya.composer.acceptFollow(ava.id)
                    val index = Index.build(maya.state.value)
                    index.messages[thread].orEmpty().filter { it.dto.authorId == ava.id && answered.add(it.id) }.forEach {
                        maya.composer.sendMessage(thread, MessagePayload(MessageType.TEXT, text = "Got it", replyTo = it.id))
                    }
                }
                delay(2000)
            }
        } finally {
            scope.cancel()
        }
    }
}
