package io.nekohasekai.screensharing.app

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.nekohasekai.screensharing.App
import kotlinx.coroutines.CompletableDeferred

class RuntimePermission(
    private val permission: String,
    private val minSdk: Int = Build.VERSION_CODES.BASE,
    private val onGranted: () -> Unit = {},
) {
    private var pending: CompletableDeferred<Boolean>? = null
    private var pendingRequester: Requester? = null

    val isGranted: Boolean
        get() = Build.VERSION.SDK_INT < minSdk || ContextCompat.checkSelfPermission(App.instance, permission) == PackageManager.PERMISSION_GRANTED

    inner class Requester internal constructor() {
        internal lateinit var launcher: ActivityResultLauncher<String>

        suspend fun request(): Boolean {
            if (isGranted) return true
            pending?.let { return it.await() }
            val request = CompletableDeferred<Boolean>()
            pending = request
            pendingRequester = this
            launcher.launch(permission)
            return request.await()
        }

        internal fun release() {
            if (pendingRequester === this) complete(isGranted)
        }
    }

    private fun complete(granted: Boolean) {
        val request = pending
        pending = null
        pendingRequester = null
        if (granted) onGranted()
        request?.complete(granted)
    }

    fun requester(activity: ComponentActivity): Requester {
        val requester = Requester()
        requester.launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission(), ::complete)
        activity.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY && !activity.isChangingConfigurations) requester.release()
            },
        )
        return requester
    }

    @Composable
    fun rememberRequester(): Requester {
        val activity = LocalActivity.current
        val requester = remember { Requester() }
        requester.launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), ::complete)
        DisposableEffect(requester) {
            onDispose {
                if (activity?.isChangingConfigurations != true) requester.release()
            }
        }
        return requester
    }
}
