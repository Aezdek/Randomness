package xyz.aezdek.hush.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import xyz.aezdek.hush.crypto.KdfParams
import xyz.aezdek.hush.protocol.DeviceInfo
import xyz.aezdek.hush.protocol.InviteDto
import xyz.aezdek.hush.protocol.MessagePayload
import xyz.aezdek.hush.protocol.MessageType
import xyz.aezdek.hush.protocol.PostPayload
import xyz.aezdek.hush.protocol.ProfilePayload
import xyz.aezdek.hush.protocol.StoryPayload
import xyz.aezdek.hush.protocol.StoryText
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * A server with a lived-in amount of data, for measuring how the web app copes (web/e2e/perf.spec.ts). Skipped unless
 * HUSH_PERF_URL is set. Ana (the account the browser signs in to) and Bo follow each other; Bo has posts and stories,
 * and their chat has a long history. Then Bo keeps talking, one message a second, while the browser measures.
 */
class PerfSeed {
    @Test
    fun seed() = runBlocking {
        val url = System.getenv("HUSH_PERF_URL")
        assumeTrue("Only for measuring the web app", !url.isNullOrBlank())
        val invite = File(System.getenv("HUSH_PERF_INVITE")!!).readText().trim()
        val out = File(System.getenv("HUSH_PERF_OUT")!!)
        val messages = (System.getenv("HUSH_PERF_MESSAGES") ?: "1500").toInt()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        fun client(name: String) = AezClient(url, http, MemoryTables(), MemorySecretStore(), DeviceInfo(name, "web"), scope, KdfParams.FAST_FOR_TESTS)
        try {
            val ana = client("ana").apply { signUp(invite, "ana", "tangerine kettle orbit") }
            val code: InviteDto = ana.api.post("/admin/invites")
            val bo = client("bo").apply { signUp(code.code, "bo", "quiet harbour lantern") }
            ana.composer.saveProfile(ProfilePayload(displayName = "Ana Lima", bio = "Here for the photos"))
            bo.composer.saveProfile(ProfilePayload(displayName = "Bo Chen", bio = "Long walks, short films"))
            ana.engine.sync(); bo.engine.sync()
            ana.composer.follow(bo.state.value.users.values.first { it.username == "bo" }.id)
            bo.composer.follow(ana.state.value.me!!.id)
            bo.engine.sync(); ana.engine.sync()
            bo.state.value.followRequests.forEach { bo.composer.acceptFollow(it) }
            ana.state.value.followRequests.forEach { ana.composer.acceptFollow(it) }
            ana.engine.sync(); bo.engine.sync()

            val dir = File(out.parentFile, "perf-photos").apply { mkdirs() }
            val photos = (0 until 8).map { i ->
                File(dir, "p$i.jpg").also { file ->
                    val image = BufferedImage(1080, 1350, BufferedImage.TYPE_INT_RGB)
                    val g = image.createGraphics()
                    for (y in 0 until 1350 step 30) {
                        g.color = Color.getHSBColor((i * 0.12f + y / 4000f) % 1f, 0.5f, 0.85f)
                        g.fillRect(0, y, 1080, 30)
                    }
                    g.dispose()
                    ImageIO.write(image, "jpg", file)
                }
            }
            val refs = photos.map { bo.media.upload(PreparedMedia(it, "image/jpeg", 1080, 1350, blurhash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj")) }
            repeat(40) { i -> bo.composer.createPost(PostPayload(caption = "Day $i #film", media = listOf(refs[i % refs.size]))) }
            repeat(3) { i -> bo.composer.createStory(StoryPayload(text = StoryText("Story $i")), closeFriends = false) }
            bo.engine.sync()
            val anaId = ana.state.value.me!!.id
            val thread = bo.composer.openDirect(anaId)
            var last: Entity? = null
            repeat(messages) { i ->
                val sender = if (i % 3 == 0) ana else bo
                if (i % 3 == 0 && i > 0) ana.engine.sync()
                val payload = when {
                    i % 97 == 0 -> MessagePayload(MessageType.MEDIA, media = listOf(refs[i % refs.size]))
                    i % 11 == 0 && last != null -> MessagePayload(MessageType.TEXT, text = "Replying to that, message $i", replyTo = last!!.id)
                    else -> MessagePayload(MessageType.TEXT, text = "Message number $i — " + "lorem ipsum dolor sit amet ".repeat(1 + i % 4))
                }
                val sent = sender.composer.sendMessage(thread, payload)
                last = sent
                if (i % 23 == 1) { bo.engine.sync(); bo.composer.react(bo.state.value.entities[sent.id] ?: sent, "🔥") }
            }
            out.writeText("thread=$thread\nready=1\n")
            println("perf: seeded $messages messages")
            // Live: Bo keeps talking while the browser measures.
            val live = File(out.parentFile, "perf-live")
            while (!File(out.parentFile, "perf-stop").exists()) {
                if (live.exists()) bo.composer.sendMessage(thread, MessagePayload(MessageType.TEXT, text = "Live ${System.currentTimeMillis()}"))
                delay(1000)
            }
        } finally {
            scope.cancel()
        }
    }
}
