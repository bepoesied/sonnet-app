package pw.kmr.sonnet

import android.app.Application
import pw.kmr.sonnet.core.AppContainer

class SonnetApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }
}