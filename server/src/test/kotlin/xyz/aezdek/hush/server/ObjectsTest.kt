package xyz.aezdek.hush.server

import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.aezdek.hush.crypto.B64
import xyz.aezdek.hush.crypto.ObjectCrypto
import xyz.aezdek.hush.protocol.ArchiveRequest
import xyz.aezdek.hush.protocol.FollowResult
import xyz.aezdek.hush.protocol.FollowState
import xyz.aezdek.hush.protocol.IdList
import xyz.aezdek.hush.protocol.ObjectDto
import xyz.aezdek.hush.protocol.UpdateMeRequest
import xyz.aezdek.hush.protocol.ViewDto
import xyz.aezdek.hush.protocol.WrapTaskDto
import xyz.aezdek.hush.protocol.WrapUpload
import xyz.aezdek.hush.protocol.WrapsRequest

class ObjectsTest {

    /** What a client does with its wrap tasks: unwrap its own key, seal it to the newcomer, report done. */
    private suspend fun Member.processWrapTasks(directory: Map<String, Member>) {
        val tasks = get("/api/v1/wrap-tasks").body<List<WrapTaskDto>>()
        for (task in tasks) {
            val recipient = directory.getValue(task.recipientId)
            val wraps = task.objects.map { obj ->
                val key = ObjectCrypto.unwrap(B64.decode(obj.wrap!!), keys, B64.decode(obj.commitment))
                WrapUpload(obj.id, recipient.id, B64.encode(ObjectCrypto.wrap(key, recipient.keys.boxPk)))
            }
            if (wraps.isNotEmpty()) assertEquals(HttpStatusCode.OK, post("/api/v1/wraps", WrapsRequest(wraps)).status)
            post("/api/v1/wrap-tasks/${task.id}/done", null)
        }
    }

    @Test
    fun aPostSealedBeforeTheAuthorHeardOfANewMemberStillReachesThem() = serverTest {
        val (alice, bob) = members("alice", "bob")
        val directory = listOf(alice, bob).associateBy { it.id }
        // Alice's client handles the join-time task first, while it has nothing to seal...
        alice.processWrapTasks(directory)
        assertTrue(alice.get("/api/v1/wrap-tasks").body<List<WrapTaskDto>>().isEmpty())

        // ...then posts to all members with a member list from before bob joined.
        val (post) = alice.seal("post", "members", "hello everyone")
        assertEquals(HttpStatusCode.Created, alice.create(post).status)
        val tasks = alice.get("/api/v1/wrap-tasks").body<List<WrapTaskDto>>()
        assertEquals(listOf(bob.id), tasks.map { it.recipientId })
        assertEquals(listOf(post.id), tasks.single().objects.map { it.id })

        alice.processWrapTasks(directory)
        bob.sync()
        assertEquals("hello everyone", bob.open(bob.received.getValue(post.id), alice))
    }

    @Test
    fun finishingATaskFetchedBeforeANewPostKeepsItOpenForThatPost() = serverTest {
        val (alice, bob) = members("alice", "bob")
        val directory = listOf(alice, bob).associateBy { it.id }
        // Alice's client fetches the join-time task while there is nothing to seal...
        val task = alice.get("/api/v1/wrap-tasks").body<List<WrapTaskDto>>().single { it.recipientId == bob.id }
        assertTrue(task.objects.isEmpty())
        // ...meanwhile posts without bob (its member list is older than his account)...
        val (post) = alice.seal("post", "members", "made meanwhile")
        assertEquals(HttpStatusCode.Created, alice.create(post).status)
        // ...and reports the task done. That must not drop the new post.
        alice.post("/api/v1/wrap-tasks/${task.id}/done", null)
        val open = alice.get("/api/v1/wrap-tasks").body<List<WrapTaskDto>>()
        assertEquals(listOf(post.id), open.single { it.recipientId == bob.id }.objects.map { it.id })

        alice.processWrapTasks(directory)
        assertTrue(alice.get("/api/v1/wrap-tasks").body<List<WrapTaskDto>>().isEmpty())
        bob.sync()
        assertEquals("made meanwhile", bob.open(bob.received.getValue(post.id), alice))
    }

    @Test
    fun followersOfAPrivateAccountGetOldPostsThroughWrapTasks() = serverTest {
        val (alice, bob, carol) = members("alice", "bob", "carol")
        val directory = listOf(alice, bob, carol).associateBy { it.id }

        val (post) = alice.seal("post", "followers", "secret caption")
        assertEquals(HttpStatusCode.Created, alice.create(post).status)

        // Not following: no access.
        assertEquals(HttpStatusCode.NotFound, bob.get("/api/v1/objects/${post.id}").status)
        bob.sync()
        assertFalse(post.id in bob.received)

        // Request → accept → alice's client seals the post to bob.
        assertEquals(FollowState.REQUESTED, bob.post("/api/v1/follows/${alice.id}", null).body<FollowResult>().state)
        assertEquals(listOf(bob.id), alice.get("/api/v1/follows/requests").body<IdList>().ids)
        alice.post("/api/v1/follows/requests/${bob.id}/accept", null)
        alice.processWrapTasks(directory)

        bob.sync()
        val received = bob.received.getValue(post.id)
        assertEquals("secret caption", bob.open(received, alice))
        assertEquals(HttpStatusCode.NotFound, carol.get("/api/v1/objects/${post.id}").status)

        // Comments and likes derive their keys from the post and reach every reader.
        val postKey = ObjectCrypto.unwrap(B64.decode(received.wrap!!), bob.keys, B64.decode(received.commitment))
        val commentId = newId()
        val (comment) = bob.seal(
            "comment", "parent", "nice!", objectId = commentId, parentId = post.id,
            key = ObjectCrypto.childKey(postKey, commentId), wrap = false,
        )
        assertEquals(HttpStatusCode.Created, bob.create(comment).status)
        alice.sync()
        val got = alice.received.getValue(commentId)
        assertNull(got.wrap)
        val aliceKey = ObjectCrypto.unwrap(B64.decode(alice.received.getValue(post.id).wrap!!), alice.keys, B64.decode(post.commitment))
        assertEquals("nice!", alice.open(got, bob, ObjectCrypto.childKey(aliceKey, commentId)))

        // Unfollowing a private account takes the post (and so its comments) away.
        bob.delete("/api/v1/follows/${alice.id}")
        bob.sync()
        assertTrue(post.id in bob.deleted)
        assertEquals(HttpStatusCode.NotFound, bob.get("/api/v1/objects/${post.id}").status)
        // Bob still sees his own comment (he wrote it), but nobody new can be shown the post through it.
        assertEquals(HttpStatusCode.NotFound, carol.get("/api/v1/objects/$commentId").status)
    }

    @Test
    fun newMembersReceivePublicContentAndProfilesThroughWrapTasks() = serverTest {
        val (alice) = members("alice")
        alice.patch("/api/v1/me", UpdateMeRequest(isPrivate = false))
        val (profile) = alice.seal("profile", "members", """{"displayName":"Alice"}""")
        alice.create(profile)
        val (public) = alice.seal("post", "members", "hello world")
        alice.create(public)

        val dave = Member(this, "dave").also { it.signup(newInvite(alice)) }
        alice.processWrapTasks(mapOf(dave.id to dave))
        dave.sync()
        assertEquals("hello world", dave.open(dave.received.getValue(public.id), alice))
        assertEquals("""{"displayName":"Alice"}""", dave.open(dave.received.getValue(profile.id), alice))

        // A second profile is refused; edits go through PUT with the same key.
        val (dup) = alice.seal("profile", "members", "{}")
        assertEquals(HttpStatusCode.Conflict, alice.create(dup).status)
    }

    @Test
    fun theServerRejectsMalformedObjects() = serverTest {
        val (alice, bob) = members("alice", "bob")
        // No self-wrap.
        val (noSelf) = alice.seal("post", "followers", "x")
        assertEquals(HttpStatusCode.BadRequest, alice.create(noSelf.copy(wraps = emptyList())).status)
        // Wrong audience for a kind.
        val (badAudience) = alice.seal("profile", "followers", "x")
        assertEquals(HttpStatusCode.BadRequest, alice.create(badAudience).status)
        // Story without expiry, and with one too far away.
        val (story) = alice.seal("story", "followers", "x")
        assertEquals(HttpStatusCode.BadRequest, alice.create(story).status)
        val (farStory) = alice.seal("story", "followers", "x", expiresAt = clock.now() + 48 * 3_600_000L)
        assertEquals(HttpStatusCode.BadRequest, alice.create(farStory).status)
        // A comment on something you cannot read.
        val (post) = alice.seal("post", "followers", "x")
        alice.create(post)
        val (comment) = bob.seal("comment", "parent", "x", parentId = post.id, wrap = false)
        assertEquals(HttpStatusCode.NotFound, bob.create(comment).status)
        // Settings are private to their author.
        val (settings) = alice.seal("settings", "self", "{}", listOf(bob))
        assertEquals(HttpStatusCode.BadRequest, alice.create(settings).status)
        // Unknown media.
        val (withBlob) = alice.seal("post", "followers", "x", blobIds = listOf("0".repeat(64)))
        assertEquals(HttpStatusCode.BadRequest, alice.create(withBlob).status)
    }

    @Test
    fun likesAreOnePerPersonAndEditsKeepTheKey() = serverTest {
        val (alice, bob) = members("alice", "bob")
        alice.patch("/api/v1/me", UpdateMeRequest(isPrivate = false))
        val (post, key) = alice.seal("post", "members", "v1", listOf(bob))
        alice.create(post)
        val (like) = bob.seal("like", "parent", "{}", parentId = post.id, wrap = false)
        assertEquals(HttpStatusCode.Created, bob.create(like).status)
        val (again) = bob.seal("like", "parent", "{}", parentId = post.id, wrap = false)
        assertEquals(HttpStatusCode.Conflict, bob.create(again).status)

        val (v2) = alice.seal("post", "members", "v2", objectId = post.id, key = key, version = 2, createdAt = post.createdAt)
        assertEquals(HttpStatusCode.OK, alice.put("/api/v1/objects/${post.id}", v2.copy(wraps = emptyList())).status)
        bob.sync()
        assertEquals("v2", bob.open(bob.received.getValue(post.id), alice))
        // Changing the key on edit is refused (existing wraps would no longer match).
        val (v3) = alice.seal("post", "members", "v3", objectId = post.id, version = 3, createdAt = post.createdAt)
        assertEquals(HttpStatusCode.BadRequest, alice.put("/api/v1/objects/${post.id}", v3).status)
    }

    @Test
    fun storiesExpireForViewersButStayInTheAuthorsArchive() = serverTest {
        val (alice, bob) = members("alice", "bob")
        val (story) = alice.seal("story", "followers", "ephemeral", listOf(bob), expiresAt = clock.now() + 24 * 3_600_000L)
        assertEquals(HttpStatusCode.Created, alice.create(story).status)
        bob.sync()
        assertTrue(story.id in bob.received)
        bob.post("/api/v1/objects/${story.id}/views", null)
        assertEquals(listOf(bob.id), alice.get("/api/v1/objects/${story.id}/views").body<List<ViewDto>>().map { it.userId })

        clock.advance(24 * 3_600_000L + 1)
        services.jobs.expireNow()
        bob.sync()
        assertTrue(story.id in bob.deleted)
        assertEquals(HttpStatusCode.NotFound, bob.get("/api/v1/objects/${story.id}").status)
        assertEquals(HttpStatusCode.OK, alice.get("/api/v1/objects/${story.id}").status)
    }

    @Test
    fun highlightsKeepExpiredStoriesVisible() = serverTest {
        val (alice, bob) = members("alice", "bob")
        val (story) = alice.seal("story", "followers", "keep me", listOf(bob), expiresAt = clock.now() + 3_600_000L)
        alice.create(story)
        val (highlight) = alice.seal("highlight", "followers", "{}", listOf(bob), refIds = listOf(story.id))
        alice.create(highlight)
        clock.advance(2 * 3_600_000L)
        services.jobs.expireNow()
        // Bob lost the story's own wrap but reaches it through the highlight.
        val viaHighlight = bob.get("/api/v1/objects/${story.id}").body<ObjectDto>()
        assertNull(viaHighlight.wrap)
    }

    @Test
    fun archivingHidesFromEveryoneButTheAuthor() = serverTest {
        val (alice, bob) = members("alice", "bob")
        val (post) = alice.seal("post", "followers", "x", listOf(bob))
        alice.create(post)
        alice.post("/api/v1/objects/${post.id}/archive", ArchiveRequest(true))
        bob.sync()
        assertTrue(post.id in bob.deleted)
        assertEquals(HttpStatusCode.NotFound, bob.get("/api/v1/objects/${post.id}").status)
        alice.post("/api/v1/objects/${post.id}/archive", ArchiveRequest(false))
        bob.sync()
        assertTrue(post.id in bob.received)
    }

    @Test
    fun blockingCutsAccessBothWays() = serverTest {
        val (alice, bob) = members("alice", "bob")
        val (post) = alice.seal("post", "followers", "x", listOf(bob))
        alice.create(post)
        bob.post("/api/v1/follows/${alice.id}", null)
        alice.post("/api/v1/blocks/${bob.id}", null)
        assertEquals(HttpStatusCode.NotFound, bob.get("/api/v1/objects/${post.id}").status)
        assertEquals(HttpStatusCode.Forbidden, bob.post("/api/v1/follows/${alice.id}", null).status)
        assertTrue(bob.user(alice).followState == FollowState.NONE)
    }

    @Test
    fun goingPrivateRemovesNonFollowers() = serverTest {
        val (alice, bob) = members("alice", "bob")
        alice.patch("/api/v1/me", UpdateMeRequest(isPrivate = false))
        val (post) = alice.seal("post", "members", "x", listOf(bob))
        val (profile) = alice.seal("profile", "members", "{}", listOf(bob))
        alice.create(post)
        alice.create(profile)
        alice.patch("/api/v1/me", UpdateMeRequest(isPrivate = true))
        assertEquals(HttpStatusCode.NotFound, bob.get("/api/v1/objects/${post.id}").status)
        assertNotNull(bob.get("/api/v1/objects/${profile.id}").body<ObjectDto>().wrap)
    }

    @Test
    fun deletingAPostDeletesItsComments() = serverTest {
        val (alice, bob) = members("alice", "bob")
        val (post, key) = alice.seal("post", "followers", "x", listOf(bob))
        alice.create(post)
        val commentId = newId()
        val (comment) = bob.seal("comment", "parent", "c", objectId = commentId, parentId = post.id, key = ObjectCrypto.childKey(key, commentId), wrap = false)
        bob.create(comment)
        assertEquals(HttpStatusCode.Forbidden, bob.delete("/api/v1/objects/${post.id}").status)
        alice.delete("/api/v1/objects/${post.id}")
        assertEquals(HttpStatusCode.NotFound, alice.get("/api/v1/objects/$commentId").status)
        bob.sync()
        assertTrue(post.id in bob.deleted)
    }
}
