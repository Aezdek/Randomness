package xyz.aezdek.daybook.web

// Small bridges to browser APIs. Each one fails soft: storage can be full or disabled (private windows),
// and the app must still start.

internal fun storageGet(key: String): String? =
    js("(() => { try { return window.localStorage.getItem(key); } catch (e) { return null; } })()")

internal fun storageSet(key: String, value: String): Boolean =
    js("(() => { try { window.localStorage.setItem(key, value); return true; } catch (e) { return false; } })()")

/** The address this page was loaded from, such as https://daybook.aezdek.xyz. */
internal fun pageOrigin(): String = js("window.location.origin")

internal fun prefersReducedMotion(): Boolean = js("window.matchMedia('(prefers-reduced-motion: reduce)').matches")

/** Calls [onChange] with true when the tab becomes visible and false when it is hidden. */
internal fun onVisibilityChange(onChange: (Boolean) -> Unit): Unit =
    js("document.addEventListener('visibilitychange', () => onChange(document.visibilityState === 'visible'))")

internal fun onEscapeKey(onEscape: () -> Unit): Unit =
    js("document.addEventListener('keydown', (e) => { if (e.key === 'Escape') onEscape(); })")

/** Removes the plain-HTML loading message once Compose is ready to draw. */
internal fun hideLoadingMessage(): Unit = js("document.getElementById('loading')?.remove()")
