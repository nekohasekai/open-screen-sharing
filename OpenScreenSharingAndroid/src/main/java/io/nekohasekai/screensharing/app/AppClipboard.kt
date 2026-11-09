package io.nekohasekai.screensharing.app

import android.content.ClipData
import android.content.ClipboardManager
import io.nekohasekai.screensharing.App

object AppClipboard {
    fun write(text: String) {
        val clipboard = App.instance.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
    }
}
