package pw.kmr.sonnet.auth

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import pw.kmr.sonnet.R
import pw.kmr.sonnet.shared.auth.AuthCallbackResult

class AuthBrowserActivity : ComponentActivity() {
    private var authStarted: Boolean = false
    private var authorizeUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            authStarted = savedInstanceState.getBoolean(KEY_AUTH_STARTED, false)
            authorizeUrl = savedInstanceState.getString(KEY_AUTHORIZE_URL)
        } else {
            authorizeUrl = intent.getStringExtra(EXTRA_AUTHORIZE_URL)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_AUTH_STARTED, authStarted)
        outState.putString(KEY_AUTHORIZE_URL, authorizeUrl)
    }

    override fun onResume() {
        super.onResume()

        val callbackUri = intent.getStringExtra(EXTRA_CALLBACK_URI)?.takeIf { it.isNotBlank() }
        if (callbackUri != null) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_CALLBACK_URI, callbackUri))
            finish()
            return
        }

        if (!authStarted) {
            val targetUrl = authorizeUrl?.takeIf { it.isNotBlank() }
            if (targetUrl == null) {
                setResult(RESULT_CANCELED)
                finish()
                return
            }

            authStarted = true
            try {
                browserLauncher(this, targetUrl.toUri())
            } catch (_: ActivityNotFoundException) {
                setResult(
                    RESULT_ERROR,
                    Intent().putExtra(EXTRA_ERROR_MESSAGE, getString(R.string.login_no_browser_error))
                )
                finish()
            }
            return
        }

        setResult(RESULT_CANCELED)
        finish()
    }

    companion object {
        const val EXTRA_AUTHORIZE_URL = "pw.kmr.sonnet.extra.AUTHORIZE_URL"
        const val EXTRA_CALLBACK_URI = "pw.kmr.sonnet.extra.CALLBACK_URI"
        const val EXTRA_ERROR_MESSAGE = "pw.kmr.sonnet.extra.ERROR_MESSAGE"
        const val RESULT_ERROR = RESULT_FIRST_USER

        private const val KEY_AUTH_STARTED = "auth_started"
        private const val KEY_AUTHORIZE_URL = "authorize_url"

        internal var browserLauncher: (Activity, Uri) -> Unit = ::launchCustomTab

        fun createIntent(context: Context, authorizeUrl: String): Intent =
            Intent(context, AuthBrowserActivity::class.java).apply {
                putExtra(EXTRA_AUTHORIZE_URL, authorizeUrl)
            }

        fun createCallbackIntent(context: Context, callbackUri: String): Intent =
            Intent(context, AuthBrowserActivity::class.java).apply {
                putExtra(EXTRA_CALLBACK_URI, callbackUri)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }

        fun parseResult(resultCode: Int, data: Intent?): AuthCallbackResult = when (resultCode) {
            RESULT_OK -> {
                val callbackUri = data?.getStringExtra(EXTRA_CALLBACK_URI)?.takeIf { it.isNotBlank() }
                if (callbackUri != null) {
                    AuthCallbackResult.Callback(callbackUri)
                } else {
                    AuthCallbackResult.Cancelled
                }
            }

            RESULT_ERROR -> AuthCallbackResult.Failed(
                data?.getStringExtra(EXTRA_ERROR_MESSAGE).orEmpty()
            )

            else -> AuthCallbackResult.Cancelled
        }

        internal fun resetBrowserLauncher() {
            browserLauncher = ::launchCustomTab
        }

        private fun launchCustomTab(activity: Activity, uri: Uri) {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                .build()
                .launchUrl(activity, uri)
        }
    }
}
