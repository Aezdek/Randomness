package xyz.aezdek.hush.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf

/** Every screen in the app. Tabs reset the back stack; everything else pushes onto it. */
sealed interface Screen {
    val tab: Boolean get() = false

    data object Home : Screen { override val tab = true }
    data class Explore(val search: Boolean = false) : Screen { override val tab = true }
    data class Reels(val startId: String? = null) : Screen { override val tab = true }
    data class Profile(val userId: String) : Screen
    data object Activity : Screen
    data object Inbox : Screen
    data class Chat(val threadId: String) : Screen
    data object NewChat : Screen
    data class ThreadInfo(val threadId: String) : Screen
    data class Post(val id: String) : Screen
    data class Stories(val userIds: List<String>, val start: Int = 0) : Screen
    data class Highlight(val highlightId: String) : Screen
    data class Hashtag(val tag: String) : Screen
    data class FollowList(val userId: String, val followers: Boolean) : Screen
    data object EditProfile : Screen
    data class Settings(val section: String? = null) : Screen
    data class Verify(val userId: String) : Screen
    data object Readiness : Screen
    data class Create(val kind: CreateKind) : Screen
}

enum class CreateKind { POST, STORY, REEL, NOTE }

class Navigator {
    val stack = mutableStateListOf<Screen>(Screen.Home)

    val current: Screen get() = stack.last()

    fun go(screen: Screen) {
        if (screen.tab) {
            stack.clear()
            stack.add(screen)
        } else if (stack.lastOrNull() != screen) {
            stack.add(screen)
        }
    }

    /** Opens [screen] as the profile tab's root (your own profile from the bottom bar). */
    fun root(screen: Screen) {
        stack.clear()
        stack.add(screen)
    }

    fun back(): Boolean {
        if (stack.size <= 1) {
            if (stack.lastOrNull() != Screen.Home) {
                root(Screen.Home)
                return true
            }
            return false
        }
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Replaces the current screen (e.g. a create flow that finishes on the thing it made). */
    fun replace(screen: Screen) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        stack.add(screen)
    }
}

val LocalNav = staticCompositionLocalOf<Navigator> { error("Navigator not provided") }
