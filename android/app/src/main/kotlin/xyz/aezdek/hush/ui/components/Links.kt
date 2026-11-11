package xyz.aezdek.hush.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * A link from someone's profile or story, as a web address, or null. Their content is encrypted, so nothing has
 * vetted it: only http and https get through, never `intent:`, `file:`, `content:`, `tel:` or another app's own scheme.
 */
fun webLink(raw: String?): Uri? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    val uri = Uri.parse(if (Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(text)) text else "https://$text")
    val scheme = uri.scheme?.lowercase()
    return uri.takeIf { (scheme == "https" || scheme == "http") && !it.host.isNullOrBlank() }
}

/** Opens [raw] in the browser if it is a web address; does nothing otherwise. */
fun openWebLink(context: Context, raw: String?) {
    val uri = webLink(raw) ?: return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
}
