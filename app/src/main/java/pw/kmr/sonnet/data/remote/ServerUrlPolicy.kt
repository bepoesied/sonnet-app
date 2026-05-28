package pw.kmr.sonnet.data.remote

import android.net.Uri

class ServerUrlPolicy(val enforcesHttps: Boolean) {
    fun isAllowed(serverUrl: String): Boolean {
        val uri = Uri.parse(serverUrl)
        return uri.host != null && uri.scheme in setOf("http", "https") && (!enforcesHttps || uri.scheme == "https")
    }
}
