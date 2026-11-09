package io.nekohasekai.screensharing

import android.app.Application
import io.nekohasekai.screensharing.session.DecoderCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        DecoderCapabilities.prepare()
    }

    companion object {
        lateinit var instance: App
            private set

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
