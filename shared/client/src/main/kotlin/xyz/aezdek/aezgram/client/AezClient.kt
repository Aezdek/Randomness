package xyz.aezdek.aezgram.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import xyz.aezdek.aezgram.crypto.AccountKeys
import xyz.aezdek.aezgram.crypto.B64
import xyz.aezdek.aezgram.crypto.KdfParams
import xyz.aezdek.aezgram.crypto.ObjectCrypto
import xyz.aezdek.aezgram.crypto.PasswordKeys
import xyz.aezdek.aezgram.crypto.SignalCrypto
import xyz.aezdek.aezgram.protocol.AuthResponse
import xyz.aezdek.aezgram.protocol.CallSignal
import xyz.aezdek.aezgram.protocol.ChangePasswordRequest
import xyz.aezdek.aezgram.protocol.DeleteAccountRequest
import xyz.aezdek.aezgram.protocol.DeviceInfo
import xyz.aezdek.aezgram.protocol.LoginRequest
import xyz.aezdek.aezgram.protocol.Me
import xyz.aezdek.aezgram.protocol.PreloginRequest
import xyz.aezdek.aezgram.protocol.PreloginResponse
import xyz.aezdek.aezgram.protocol.ResetKeysRequest
import xyz.aezdek.aezgram.protocol.SignalDto
import xyz.aezdek.aezgram.protocol.SignupRequest
import xyz.aezdek.aezgram.protocol.UpdateMeRequest
import xyz.aezdek.aezgram.protocol.WrapUpload
import xyz.aezdek.aezgram.protocol.WsFrame
import xyz.aezdek.aezgram.protocol.WsType

/**
 * One signed-in (or signed-out) AezGram account on this device: everything the Android UI talks to. The platform
 * supplies storage ([tables], [secrets]) and an HTTP client; the rest is shared, tested code.
 */
class AezClient(
    baseUrl: String,
    http: OkHttpClient,
    private val tables: Tables,
    private val secrets: SecretStore,
    private val device: DeviceInfo,
    val scope: CoroutineScope,
    private val kdf: KdfParams = KdfParams.DEFAULT,
) {
    val api = Api(baseUrl, http)
    val state = MutableStateFlow(AppState())
    val engine = SyncEngine(api, state, scope)
    val realtime = Realtime(api, state, scope)
    val media = MediaStore(api)

    /** Rebuilt only when the entities or our account change, like the web client's memoised selectors. */
    val index: StateFlow<Index> = state
        .map { it.entities to it.me?.id }
        .distinctUntilChanged { a, b -> a.first === b.first && a.second == b.second }
        .map { Index.build(state.value) }
        .stateIn(scope, SharingStarted.Eagerly, Index.EMPTY)

    val composer = Composer(api, state, engine) { Index.build(state.value) }

    /** Called when the server says our session is gone (revoked from another device, or the account deleted). */
    var onSignedOut: (() -> Unit)? = null

    init {
        api.onUnauthorized = {
            scope.launch { signOut(tellServer = false) }
        }
        scope.launch { realtime.frames.collect(::onFrame) }
    }

    val signedIn: Boolean get() = state.value.me != null

    // ------------------------------------------------------------------------------------------------------ session

    /** Resumes the stored session, if any. Returns whether we are signed in. */
    suspend fun restore(): Boolean {
        val stored = withContext(Dispatchers.IO) { secrets.load() } ?: return false
        start(stored.token, stored.me, stored.keys())
        return true
    }

    private suspend fun start(token: String, me: Me, keys: AccountKeys) {
        api.token = token
        state.update { it.copy(me = me, keys = keys) }
        withContext(Dispatchers.IO) { engine.open(LocalDb(tables)) }
        realtime.connect()
        scope.launch { runCatching { engine.sync() } }
        scope.launch {
            runCatching {
                val fresh: Me = api.get("/me")
                state.update { it.copy(me = fresh) }
                secrets.save(StoredSession.of(token, fresh, keys))
            }
        }
    }

    suspend fun signUp(invite: String, username: String, password: String) {
        val salt = PasswordKeys.newSalt()
        val derived = withContext(Dispatchers.Default) { PasswordKeys.derive(password, salt, kdf) }
        val keys = AccountKeys.generate()
        val pub = keys.publicKeys()
        val response: AuthResponse = api.post(
            "/auth/signup",
            SignupRequest(
                inviteCode = invite.trim().uppercase(),
                username = username.trim().lowercase(),
                salt = B64.encode(salt),
                opsLimit = kdf.opsLimit,
                memLimit = kdf.memLimitBytes,
                authKey = B64.encode(derived.authKey),
                signPk = B64.encode(pub.signPk),
                boxPk = B64.encode(pub.boxPk),
                boxPkSig = B64.encode(pub.boxPkSig),
                vault = B64.encode(keys.sealVault(derived.vaultKey)),
                device = device,
            ),
        )
        secrets.save(StoredSession.of(response.token, response.me, keys))
        start(response.token, response.me, keys)
    }

    suspend fun signIn(username: String, password: String) {
        val name = username.trim().lowercase()
        val pre: PreloginResponse = api.post("/auth/prelogin", PreloginRequest(name))
        val derived = withContext(Dispatchers.Default) {
            PasswordKeys.derive(password, B64.decode(pre.salt), KdfParams(pre.opsLimit, pre.memLimit))
        }
        val response: AuthResponse = api.post("/auth/login", LoginRequest(name, B64.encode(derived.authKey), device))
        val keys = AccountKeys.openVault(B64.decode(response.vault), derived.vaultKey)
        secrets.save(StoredSession.of(response.token, response.me, keys))
        start(response.token, response.me, keys)
    }

    suspend fun signOut(tellServer: Boolean = true) {
        if (state.value.me == null && api.token == null) return
        if (tellServer) runCatching { api.post<Unit>("/auth/logout") }
        realtime.close()
        engine.close()
        api.token = null
        withContext(Dispatchers.IO) {
            secrets.clear()
            tables.clear()
        }
        state.value = AppState()
        onSignedOut?.invoke()
    }

    private suspend fun derive(password: String): PasswordKeys.Derived {
        val me = state.value.me ?: throw UserFacingException("Not signed in")
        val pre: PreloginResponse = api.post("/auth/prelogin", PreloginRequest(me.username))
        return withContext(Dispatchers.Default) { PasswordKeys.derive(password, B64.decode(pre.salt), KdfParams(pre.opsLimit, pre.memLimit)) }
    }

    /** Same keys, re-sealed under the new password; other devices are signed out. */
    suspend fun changePassword(current: String, next: String) {
        val keys = state.value.keys ?: return
        val old = derive(current)
        val salt = PasswordKeys.newSalt()
        val fresh = withContext(Dispatchers.Default) { PasswordKeys.derive(next, salt, kdf) }
        api.post<Unit, ChangePasswordRequest>(
            "/auth/password",
            ChangePasswordRequest(B64.encode(old.authKey), B64.encode(salt), kdf.opsLimit, kdf.memLimitBytes, B64.encode(fresh.authKey),
                B64.encode(keys.sealVault(fresh.vaultKey))),
        )
    }

    suspend fun deleteAccount(password: String) {
        val derived = derive(password)
        api.post<Unit, DeleteAccountRequest>("/me/delete", DeleteAccountRequest(B64.encode(derived.authKey)))
        signOut(tellServer = false)
    }

    suspend fun updateMe(isPrivate: Boolean? = null, showActivity: Boolean? = null) {
        val fresh: Me = api.patch("/me", UpdateMeRequest(isPrivate, showActivity))
        state.update { it.copy(me = fresh) }
        state.value.keys?.let { keys -> api.token?.let { secrets.save(StoredSession.of(it, fresh, keys)) } }
        engine.sync()
        engine.processWrapTasks()
    }

    /**
     * Suspected compromise: a brand-new identity. Every content key we can open is re-sealed to the new box key, the
     * old wraps are replaced, and contacts see a key-change warning. Content that leaked before stays leaked.
     */
    suspend fun resetKeys(password: String, onProgress: (String) -> Unit = {}) {
        val me = state.value.me ?: return
        onProgress("Checking your password…")
        val old = derive(password)
        onProgress("Creating new keys…")
        val fresh = AccountKeys.generate()
        val pub = fresh.publicKeys()
        val salt = PasswordKeys.newSalt()
        val derived = withContext(Dispatchers.Default) { PasswordKeys.derive(password, salt, kdf) }
        onProgress("Re-sealing your content…")
        val rewraps = state.value.entities.values.filter { it.dto.wrap != null }
            .map { WrapUpload(it.id, me.id, B64.encode(ObjectCrypto.wrap(it.key, fresh.boxPk))) }
        api.post<Unit, ResetKeysRequest>(
            "/auth/reset-keys",
            ResetKeysRequest(B64.encode(old.authKey), B64.encode(salt), kdf.opsLimit, kdf.memLimitBytes, B64.encode(derived.authKey),
                B64.encode(fresh.sealVault(derived.vaultKey)), B64.encode(pub.signPk), B64.encode(pub.boxPk), B64.encode(pub.boxPkSig), rewraps),
        )
        val freshMe: Me = api.get("/me")
        secrets.save(StoredSession.of(api.token!!, freshMe, fresh))
        state.update { it.copy(keys = fresh, me = freshMe) }
    }

    // ----------------------------------------------------------------------------------------------------- realtime

    private fun onFrame(frame: WsFrame) {
        when (frame.t) {
            WsType.SYNC -> scope.launch { runCatching { engine.sync() } }
            WsType.TYPING -> {
                val threadId = frame.threadId ?: return
                val userId = frame.userId ?: return
                val until = System.currentTimeMillis() + 5_000
                state.update { s -> s.copy(typing = s.typing + (threadId to (s.typing[threadId].orEmpty() + (userId to until)))) }
            }
            WsType.PRESENCE -> {
                val userId = frame.userId ?: return
                state.update { s -> s.copy(presence = s.presence + (userId to (frame.online == true))) }
            }
        }
    }

    fun sendTyping(threadId: String) = realtime.send(WsFrame(WsType.TYPING, threadId = threadId))

    /** Seals and signs a call signal for [to]; the server only relays the ciphertext. */
    fun sealSignal(to: String, signal: CallSignal, ring: Boolean = false): WsFrame? {
        val s = state.value
        val me = s.me ?: return null
        val keys = s.keys ?: return null
        val user = s.users[to] ?: return null
        val plain = Payloads.json.encodeToString(CallSignal.serializer(), signal).encodeToByteArray()
        val sealed = SignalCrypto.seal(plain, me.id, to, System.currentTimeMillis(), keys, B64.decode(user.boxPk))
        return WsFrame(
            t = WsType.SIGNAL, to = to, callId = signal.callId, ring = ring,
            signal = SignalDto(B64.encode(sealed.sealed), B64.encode(sealed.signature), sealed.sentAt),
        )
    }

    fun sendSignal(to: String, signal: CallSignal, ring: Boolean = false): Boolean = sealSignal(to, signal, ring)?.let(realtime::send) ?: false

    /** Verifies and opens a relayed signal against every identity key we know for the sender; null if none checks out. */
    fun openSignal(frame: WsFrame): Pair<String, CallSignal>? {
        val s = state.value
        val me = s.me ?: return null
        val keys = s.keys ?: return null
        val from = frame.from ?: return null
        val dto = frame.signal ?: return null
        val candidates = LinkedHashSet<String>()
        s.users[from]?.let { candidates += it.signPk }
        s.pins[from]?.let { candidates += it.signPk; candidates += it.history }
        for (signPk in candidates) {
            val plain = runCatching {
                SignalCrypto.open(SignalCrypto.Sealed(B64.decode(dto.sealed), B64.decode(dto.signature), dto.sentAt), from, me.id,
                    B64.decode(signPk), keys, System.currentTimeMillis())
            }.getOrNull() ?: continue
            return runCatching { from to Payloads.json.decodeFromString(CallSignal.serializer(), plain.decodeToString()) }.getOrNull()
        }
        return null
    }
}
