package io.nekohasekai.screensharing.app

import android.content.Intent
import io.nekohasekai.screensharing.session.SessionActivityRegistry

class ContinuedTask(val kind: String) {
    var onExpire: (() -> Unit)? = null

    var contentIntent: Intent? = null

    val isRunning: Boolean get() = isSubmitted && !isFinished && SessionActivityRegistry.keepsAlive

    internal var notificationID = 0
    internal var title = ""
        private set
    internal var subtitle = ""
        private set
    internal var completed = 0L
        private set
    internal var total = 0L
        private set
    private var isSubmitted = false
    private var isFinished = false

    fun submit(title: String, subtitle: String) {
        if (isSubmitted || isFinished) return
        this.title = title
        this.subtitle = subtitle
        isSubmitted = true
        SessionActivityRegistry.add(this)
    }

    fun update(title: String, subtitle: String) {
        if (title == this.title && subtitle == this.subtitle) return
        this.title = title
        this.subtitle = subtitle
        SessionActivityRegistry.taskChanged(this)
    }

    fun update(title: String, subtitle: String, completed: Long, total: Long) {
        update(title, subtitle)
        setProgress(completed, total)
    }

    fun setProgress(completed: Long, total: Long) {
        if (completed == this.completed && total == this.total) return
        this.completed = completed
        this.total = total
        SessionActivityRegistry.taskChanged(this)
    }

    fun finish(success: Boolean) {
        if (isFinished) return
        isFinished = true
        onExpire = null
        SessionActivityRegistry.remove(this)
    }

    internal fun expire() {
        if (isFinished) return
        onExpire?.invoke()
        finish(false)
    }
}
