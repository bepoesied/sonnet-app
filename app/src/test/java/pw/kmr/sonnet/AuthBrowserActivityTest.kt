package pw.kmr.sonnet

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import pw.kmr.sonnet.auth.AuthBrowserActivity
import pw.kmr.sonnet.auth.AuthCallbackActivity
import pw.kmr.sonnet.shared.auth.AuthCallbackResult

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AuthBrowserActivityTest {
    @After
    fun tearDown() {
        AuthBrowserActivity.resetBrowserLauncher()
    }

    @Test
    fun callbackActivityForwardsRedirectUriToBrowserActivity() {
        val callbackUri = "sonnet://auth/callback?code=abc&state=xyz"
        val intent = Intent(Intent.ACTION_VIEW, callbackUri.toUri())

        val controller = Robolectric.buildActivity(AuthCallbackActivity::class.java, intent).create()
        val activity = controller.get()

        val startedIntent = shadowOf(activity).nextStartedActivity
        assertEquals(AuthBrowserActivity::class.java.name, startedIntent.component?.className)
        assertEquals(callbackUri, startedIntent.getStringExtra(AuthBrowserActivity.EXTRA_CALLBACK_URI))
        assertTrue(startedIntent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(startedIntent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun browserActivityLaunchesBrowserAndReturnsCallbackResult() {
        var launchedUri: Uri? = null
        AuthBrowserActivity.browserLauncher = { _, uri -> launchedUri = uri }

        val context = RuntimeEnvironment.getApplication()
        val authorizeUrl = "https://sonnet.example.com/auth/mobile/authorize?state=xyz"
        val controller = Robolectric.buildActivity(
            AuthBrowserActivity::class.java,
            AuthBrowserActivity.createIntent(context, authorizeUrl)
        ).setup()

        assertEquals(authorizeUrl, launchedUri?.toString())

        val callbackUri = "sonnet://auth/callback?code=abc&state=xyz"
        controller.pause()
            .newIntent(AuthBrowserActivity.createCallbackIntent(context, callbackUri))
            .resume()

        val shadow = shadowOf(controller.get())
        assertTrue(controller.get().isFinishing)
        assertEquals(
            AuthCallbackResult.Callback(callbackUri),
            AuthBrowserActivity.parseResult(shadow.resultCode, shadow.resultIntent)
        )
    }

    @Test
    fun browserActivityReturnsCancelledWhenResumedWithoutCallback() {
        AuthBrowserActivity.browserLauncher = { _, _ -> }

        val context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(
            AuthBrowserActivity::class.java,
            AuthBrowserActivity.createIntent(context, "https://sonnet.example.com/auth/mobile/authorize?state=xyz")
        ).setup()

        controller.pause().resume()

        val shadow = shadowOf(controller.get())
        assertTrue(controller.get().isFinishing)
        assertEquals(
            AuthCallbackResult.Cancelled,
            AuthBrowserActivity.parseResult(shadow.resultCode, shadow.resultIntent)
        )
    }

    @Test
    fun browserActivityRecoversCallbackAfterProcessDeathRecreation() {
        AuthBrowserActivity.browserLauncher = { _, _ -> }

        val context = RuntimeEnvironment.getApplication()
        val authorizeUrl = "https://sonnet.example.com/auth/mobile/authorize?state=xyz"
        val initial = Robolectric.buildActivity(
            AuthBrowserActivity::class.java,
            AuthBrowserActivity.createIntent(context, authorizeUrl)
        ).setup()

        val savedState = Bundle()
        initial.pause().saveInstanceState(savedState)

        val callbackUri = "sonnet://auth/callback?code=recovered&state=xyz"
        val recreated = Robolectric.buildActivity(
            AuthBrowserActivity::class.java,
            AuthBrowserActivity.createCallbackIntent(context, callbackUri)
        ).create(savedState).start().resume()

        val shadow = shadowOf(recreated.get())
        assertTrue(recreated.get().isFinishing)
        assertEquals(Activity.RESULT_OK, shadow.resultCode)
        assertEquals(
            AuthCallbackResult.Callback(callbackUri),
            AuthBrowserActivity.parseResult(shadow.resultCode, shadow.resultIntent)
        )
    }

    @Test
    fun browserActivityReturnsErrorWhenNoBrowserIsAvailable() {
        AuthBrowserActivity.browserLauncher = { _, _ -> throw ActivityNotFoundException("No browser") }

        val context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(
            AuthBrowserActivity::class.java,
            AuthBrowserActivity.createIntent(context, "https://sonnet.example.com/auth/mobile/authorize?state=xyz")
        ).setup()

        val shadow = shadowOf(controller.get())
        assertTrue(controller.get().isFinishing)
        assertEquals(
            AuthCallbackResult.Failed(context.getString(R.string.login_no_browser_error)),
            AuthBrowserActivity.parseResult(shadow.resultCode, shadow.resultIntent)
        )
    }
}
