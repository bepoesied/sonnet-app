package pw.kmr.sonnet.auth

import android.os.Bundle
import androidx.activity.ComponentActivity

class AuthCallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val callbackUri = intent?.data?.toString()?.takeIf { it.isNotBlank() }
        if (callbackUri != null) {
            startActivity(AuthBrowserActivity.createCallbackIntent(this, callbackUri))
        }
        finish()
    }
}
