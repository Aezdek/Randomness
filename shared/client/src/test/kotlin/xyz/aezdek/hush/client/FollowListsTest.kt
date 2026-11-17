package xyz.aezdek.hush.client

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FollowListsTest {
    private var now = 1_000_000L
    private val asked = ArrayList<String>()
    private var answer = listOf("a", "b")
    private val lists = FollowLists({ path -> asked += path; answer }, { now })

    @Test
    fun aSecondLookComesFromWhatIsKept() = runBlocking {
        assertNull(lists.peek("u", followers = true))
        assertEquals(listOf("a", "b"), lists.get("u", followers = true, relation = "x"))
        assertEquals(listOf("a", "b"), lists.get("u", followers = true, relation = "x"))
        assertEquals(listOf("a", "b"), lists.peek("u", followers = true))
        assertEquals(listOf("/users/u/followers"), asked)
        // Followers and following are separate lists.
        lists.get("u", followers = false, relation = "x")
        assertEquals(listOf("/users/u/followers", "/users/u/following"), asked)
    }

    @Test
    fun aChangedFollowOrAnOldListAsksAgain() = runBlocking {
        lists.get("u", followers = true, relation = "x")
        answer = listOf("a", "b", "c")
        assertEquals(listOf("a", "b", "c"), lists.get("u", followers = true, relation = "y"))
        assertEquals(2, asked.size)
        now += FollowLists.FRESH_MS - 1
        lists.get("u", followers = true, relation = "y")
        assertEquals(2, asked.size)
        now += 1
        lists.get("u", followers = true, relation = "y")
        assertEquals(3, asked.size)
        // A clock set back doesn't make an old list look new.
        now -= 10 * FollowLists.FRESH_MS
        lists.get("u", followers = true, relation = "y")
        assertEquals(4, asked.size)
    }

    @Test
    fun signingOutForgetsEverything() = runBlocking {
        lists.get("u", followers = true, relation = "x")
        lists.clear()
        assertNull(lists.peek("u", followers = true))
        lists.get("u", followers = true, relation = "x")
        assertEquals(2, asked.size)
    }
}
