package io.nekohasekai.screensharing.home

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.nekohasekai.screensharing.app.MainActivity

object HomeRequest {
    enum class Pending {
        NewConnection,
        Settings,
    }

    var pending by mutableStateOf<Pending?>(null)

    fun open(context: Context, request: Pending) {
        pending = request
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
