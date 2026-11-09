package io.nekohasekai.screensharing.session

import android.util.Log
import io.nekohasekai.libsharing.Libsharing
import io.nekohasekai.libsharing.LogHandler

object LibsharingLog : LogHandler {
    private const val TAG = "libsharing"

    val installed: Unit by lazy { Libsharing.setLogHandler(this, -4) }

    override fun log(level: Int, message: String?) {
        val text = message.orEmpty()
        when {
            level < 0 -> Log.d(TAG, text)
            level < 4 -> Log.i(TAG, text)
            level < 8 -> Log.w(TAG, text)
            else -> Log.e(TAG, text)
        }
    }
}
