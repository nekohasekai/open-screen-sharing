package io.nekohasekai.screensharing.model

import android.os.Build
import android.provider.Settings
import io.nekohasekai.screensharing.App

object DeviceModel {
    val name: String
        get() = Settings.Global.getString(App.instance.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
}
