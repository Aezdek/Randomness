package xyz.aezdek.aezgram.ui.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.aezdek.aezgram.Graph
import xyz.aezdek.aezgram.client.Entity
import xyz.aezdek.aezgram.protocol.ActivityPayload
import xyz.aezdek.aezgram.protocol.ActivityType
import xyz.aezdek.aezgram.protocol.AnswerPayload
import xyz.aezdek.aezgram.protocol.CommentPayload
import xyz.aezdek.aezgram.protocol.FollowState
import xyz.aezdek.aezgram.protocol.Kind
import xyz.aezdek.aezgram.ui.LocalNav
import xyz.aezdek.aezgram.ui.Screen
import xyz.aezdek.aezgram.ui.components.AezButton
import xyz.aezdek.aezgram.ui.components.Avatar
import xyz.aezdek.aezgram.ui.components.ButtonStyle
import xyz.aezdek.aezgram.ui.components.Empty
import xyz.aezdek.aezgram.ui.components.MediaImage
import xyz.aezdek.aezgram.ui.components.SectionLabel
import xyz.aezdek.aezgram.ui.components.TopBar
import xyz.aezdek.aezgram.ui.components.ago
import xyz.aezdek.aezgram.ui.feed.post
import xyz.aezdek.aezgram.ui.messages.PersonRow
import xyz.aezdek.aezgram.ui.profile.FollowButton
import xyz.aezdek.aezgram.ui.profile.GridTile
import xyz.aezdek.aezgram.ui.profile.coverOf
import xyz.aezdek.aezgram.ui.theme.Aez

private val HASHTAG = Regex("#([\\p{L}\\p{N}_]{2,30})")

/** Search runs on this phone: captions and hashtags are encrypted, so only your own decrypted copy can be searched. */
@Composable
fun Explore() {
    val nav = LocalNav.current
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    val me = state.me ?: return
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim().lowercase().removePrefix("@").removePrefix("#")
    val posts = index.posts.filter { !it.dto.archived && state.users[it.dto.authorId]?.blockedByMe != true }
    // Accounts you don't follow yet first, then everything else, newest first.
    val explore = posts.sortedWith(compareBy<Entity> { state.users[it.dto.authorId]?.followState == FollowState.FOLLOWING }.thenByDescending { it.dto.createdAt })
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Aez.colors.glass).statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Aez.colors.elev2).padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, null, tint = Aez.colors.text3, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search people and hashtags", color = Aez.colors.text3, fontSize = 15.sp)
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = Aez.colors.text, fontSize = 15.sp),
                        cursorBrush = SolidColor(Aez.colors.text), modifier = Modifier.fillMaxWidth())
                }
                if (query.isNotEmpty()) Icon(Icons.Outlined.Close, "Clear", tint = Aez.colors.text2, modifier = Modifier.size(20.dp).clickable { query = "" })
            }
        }
        if (q.isNotEmpty()) {
            val people = state.users.values.filter { u ->
                !u.deleted && u.id != me.id && (u.username.contains(q) || index.displayName(u).lowercase().contains(q))
            }
            val tags = posts.flatMap { e -> HASHTAG.findAll(e.post()?.caption.orEmpty()).map { it.groupValues[1].lowercase() }.toList() }
                .filter { it.contains(q) }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(8)
            LazyColumn(Modifier.weight(1f)) {
                items(tags, key = { "#" + it.key }) { (tag, count) ->
                    Row(Modifier.fillMaxWidth().clickable { nav.go(Screen.Hashtag(tag)) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(46.dp).clip(CircleShape).background(Aez.colors.elev2), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Tag, null, tint = Aez.colors.text)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("#$tag", fontWeight = FontWeight.SemiBold, color = Aez.colors.text)
                            Text(if (count == 1) "1 post" else "$count posts", fontSize = 13.sp, color = Aez.colors.text2)
                        }
                    }
                }
                items(people, key = { it.id }) { user ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { PersonRow(user, index) { nav.go(Screen.Profile(user.id)) } }
                        FollowButton(user, Modifier.padding(end = 16.dp))
                    }
                }
                if (people.isEmpty() && tags.isEmpty()) item { Text("No results for “$query”.", color = Aez.colors.text3, modifier = Modifier.padding(24.dp)) }
            }
        } else if (explore.isEmpty()) {
            Empty(Icons.Outlined.Explore, "Nothing to explore yet", "Public posts from everyone on AezGram will show up here.")
        } else {
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(explore, key = { it.id }) { GridTile(it) }
            }
        }
    }
}

@Composable
fun Hashtag(tag: String) {
    val index by Graph.client.index.collectAsState()
    val tagged = index.posts.filter { !it.dto.archived && it.post()?.caption.orEmpty().lowercase().contains("#${tag.lowercase()}") }
    Column(Modifier.fillMaxSize()) {
        TopBar("#$tag", subtitle = if (tagged.size == 1) "1 post" else "${tagged.size} posts")
        LazyVerticalGrid(GridCells.Fixed(3), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (tagged.isEmpty()) item(span = { GridItemSpan(3) }) { Empty(Icons.Outlined.Tag, "No posts yet") }
            items(tagged, key = { it.id }) { GridTile(it) }
        }
    }
}

private class ActivityItem(val id: String, val at: Long, val actor: String, val text: String, val target: Entity?, val screen: Screen?)

/** Likes, comments, mentions and answers on your things, plus follow requests. Built from what you can decrypt. */
@Composable
fun Activity() {
    val nav = LocalNav.current
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    val me = state.me ?: return
    val lastSeen = rememberSaveable { Graph.prefs.lastActivitySeen }
    DisposableEffect(Unit) { onDispose { Graph.prefs.lastActivitySeen = System.currentTimeMillis() } }
    fun rootOf(e: Entity): Entity {
        var current = e
        while (true) current = current.dto.parentId?.let { state.entities[it] } ?: return current
    }
    val events = buildList {
        for (e in state.entities.values) {
            val dto = e.dto
            if (dto.authorId == me.id) continue
            val parent = dto.parentId?.let { state.entities[it] } ?: continue
            when {
                dto.kind == Kind.LIKE && parent.dto.authorId == me.id && parent.dto.kind != Kind.MESSAGE -> {
                    val what = when (parent.dto.kind) { Kind.COMMENT -> "comment"; Kind.STORY -> "story"; Kind.REEL -> "reel"; else -> "post" }
                    add(ActivityItem(dto.id, dto.receivedAt, dto.authorId, "liked your $what.", rootOf(parent), Screen.Post(rootOf(parent).id)))
                }
                dto.kind == Kind.COMMENT && rootOf(parent).dto.authorId == me.id ->
                    add(ActivityItem(dto.id, dto.receivedAt, dto.authorId, "commented: " + e.payloadAs<CommentPayload>()?.text.orEmpty().take(80),
                        rootOf(parent), Screen.Post(rootOf(parent).id)))
                dto.kind == Kind.VOTE && parent.dto.authorId == me.id ->
                    add(ActivityItem(dto.id, dto.receivedAt, dto.authorId, "responded to your story.", null, Screen.Stories(listOf(me.id))))
            }
        }
        for (a in index.activity) {
            val p = a.payloadAs<ActivityPayload>() ?: continue
            add(ActivityItem(a.id, a.dto.receivedAt, a.dto.authorId,
                if (p.type == ActivityType.TAG) "tagged you in a post." else "mentioned you: ${p.text.orEmpty()}", state.entities[p.objectId], Screen.Post(p.objectId)))
        }
        for (a in index.answers) {
            if (a.dto.authorId == me.id) continue
            add(ActivityItem(a.id, a.dto.receivedAt, a.dto.authorId, "answered your question: ${a.payloadAs<AnswerPayload>()?.text.orEmpty()}", null, null))
        }
    }.sortedByDescending { it.at }
    val followsYou = state.users.values.filter { it.followsMe == FollowState.FOLLOWING && it.followState == FollowState.NONE && !it.blockedByMe }
    Column(Modifier.fillMaxSize()) {
        TopBar("Notifications")
        LazyColumn(Modifier.weight(1f)) {
            if (state.followRequests.isNotEmpty()) {
                item { SectionLabel("Follow requests") }
                items(state.followRequests, key = { "req:$it" }) { id ->
                    val user = state.users[id] ?: return@items
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(id, 44.dp, index, user.username) { nav.go(Screen.Profile(id)) }
                        Spacer(Modifier.width(12.dp))
                        Text("${user.username} wants to follow you", Modifier.weight(1f), fontSize = 14.sp, color = Aez.colors.text)
                        AezButton("Confirm", { Graph.launch { Graph.client.composer.acceptFollow(id) } }, small = true, style = ButtonStyle.BRAND)
                        Spacer(Modifier.width(6.dp))
                        AezButton("Delete", { Graph.launch { Graph.client.composer.declineFollow(id) } }, small = true, style = ButtonStyle.SECONDARY)
                    }
                }
            }
            val groups = listOf(
                "New" to events.filter { it.at > lastSeen },
                "Earlier" to events.filter { it.at <= lastSeen },
            )
            for ((title, list) in groups) {
                if (list.isEmpty()) continue
                item { SectionLabel(title) }
                items(list, key = { it.id }) { item ->
                    Row(Modifier.fillMaxWidth().clickable { item.screen?.let(nav::go) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Avatar(item.actor, 44.dp, index, state.users[item.actor]?.username) { nav.go(Screen.Profile(item.actor)) }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "${state.users[item.actor]?.username ?: "someone"} ${item.text}  ${ago(item.at)}",
                            Modifier.weight(1f), fontSize = 14.sp, color = Aez.colors.text, maxLines = 3,
                        )
                        item.target?.let { coverOf(it) }?.let { cover ->
                            Spacer(Modifier.width(10.dp))
                            MediaImage(cover, Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)), maxEdge = 160)
                        }
                    }
                }
            }
            if (followsYou.isNotEmpty()) {
                item { SectionLabel("Follows you") }
                items(followsYou, key = { "f:" + it.id }) { user ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { PersonRow(user, index, subtitle = "started following you") { nav.go(Screen.Profile(user.id)) } }
                        FollowButton(user, Modifier.padding(end = 16.dp))
                    }
                }
            }
            if (events.isEmpty() && state.followRequests.isEmpty() && followsYou.isEmpty()) {
                item { Empty(Icons.Outlined.FavoriteBorder, "Activity on your posts", "When someone likes or comments on your posts, you'll see it here.") }
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
}
