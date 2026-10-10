package xyz.aezdek.aezprint.lock

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Tracks whether the biometric lock has been passed; it re-arms after the app sits in the background. */
@Singleton
class AppLock @Inject constructor() : DefaultLifecycleObserver {

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()
    private var backgroundedAt: Long? = null

    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    fun unlock() {
        _unlocked.value = true
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = System.currentTimeMillis()
    }

    override fun onStart(owner: LifecycleOwner) {
        val since = backgroundedAt ?: return
        if (System.currentTimeMillis() - since > RELOCK_AFTER_MS) _unlocked.value = false
    }

    private companion object {
        const val RELOCK_AFTER_MS = 60_000L
    }
}
