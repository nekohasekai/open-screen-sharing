package io.nekohasekai.screensharing.app

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.nekohasekai.screensharing.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object StorageNotice {
    const val TAG = "storage"

    var alert by mutableStateOf<AlertState?>(null)
        private set
    private val queued = mutableListOf<AlertState>()

    fun report(failure: String, error: Throwable) {
        Log.e(TAG, failure, error)
        App.scope.launch(Dispatchers.Main) {
            val alert = AlertState.failure(failure, error)
            if ((listOfNotNull(this@StorageNotice.alert) + queued).any { it.title == alert.title && it.message == alert.message }) return@launch
            if (this@StorageNotice.alert == null) {
                this@StorageNotice.alert = alert
            } else {
                queued.add(alert)
            }
        }
    }

    fun dismiss() {
        alert = null
        if (queued.isEmpty()) return
        App.scope.launch {
            if (alert == null && queued.isNotEmpty()) {
                alert = queued.removeAt(0)
            }
        }
    }
}
