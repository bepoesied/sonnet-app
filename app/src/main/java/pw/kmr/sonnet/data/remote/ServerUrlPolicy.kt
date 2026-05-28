class ServerUrlPolicy(val enforcesHttps: Boolean) {
        return uri.host != null && uri.scheme in setOf("http", "https") && (!enforcesHttps || uri.scheme == "https")
import android.net.Uri

class ServerUrlPolicy(private val enforceHttps: Boolean) {
    fun isAllowed(serverUrl: String): Boolean {
        val uri = Uri.parse(serverUrl)
        return uri.host != null && (!enforceHttps || uri.scheme == "https")
    }
}