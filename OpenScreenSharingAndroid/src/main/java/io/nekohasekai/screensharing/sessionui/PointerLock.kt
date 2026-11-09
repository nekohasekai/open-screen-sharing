package io.nekohasekai.screensharing.sessionui

import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.nekohasekai.screensharing.App

object PointerLock {
    private val inputManager = App.instance.getSystemService(InputManager::class.java)
    private val receivers = LinkedHashSet<Runnable>()

    var hasMouse by mutableStateOf(false)
        private set

    init {
        inputManager.registerInputDeviceListener(
            object : InputManager.InputDeviceListener {
                override fun onInputDeviceAdded(deviceId: Int) = update()

                override fun onInputDeviceRemoved(deviceId: Int) = update()

                override fun onInputDeviceChanged(deviceId: Int) = update()
            },
            Handler(Looper.getMainLooper()),
        )
        update()
    }

    fun addReceiver(receiver: Runnable) {
        receivers.add(receiver)
    }

    fun removeReceiver(receiver: Runnable) {
        receivers.remove(receiver)
    }

    private fun update() {
        val connected = inputManager.inputDeviceIds.any { id ->
            val device = inputManager.getInputDevice(id) ?: return@any false
            !device.isVirtual && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1 || device.isEnabled) &&
                (device.supportsSource(InputDevice.SOURCE_MOUSE) || device.supportsSource(InputDevice.SOURCE_TOUCHPAD))
        }
        if (connected == hasMouse) return
        hasMouse = connected
        for (receiver in receivers.toList()) receiver.run()
    }
}
