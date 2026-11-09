package io.nekohasekai.screensharing.model

import android.util.Log
import io.nekohasekai.screensharing.app.StorageNotice
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

object PreferenceStore {
    val json = Json { ignoreUnknownKeys = true }

    private val preferences get() = AppDatabase.shared.preferences()

    inline fun <reified T> value(name: String): T? = value(name, serializer<T>())

    fun <T> value(name: String, serializer: KSerializer<T>): T? {
        val item = try {
            preferences.get(name)
        } catch (error: Exception) {
            StorageNotice.report("Couldn’t read settings", error)
            return null
        } ?: return null
        return try {
            json.decodeFromString(serializer, item.data)
        } catch (error: Exception) {
            Log.e(StorageNotice.TAG, "remove unreadable preference $name", error)
            remove(name)
            null
        }
    }

    inline fun <reified T> set(name: String, value: T): Boolean = set(name, value, serializer<T>())

    fun <T> set(name: String, value: T, serializer: KSerializer<T>): Boolean = try {
        preferences.save(Preference(name, json.encodeToString(serializer, value)))
        true
    } catch (error: Exception) {
        StorageNotice.report("Couldn’t save settings", error)
        false
    }

    fun remove(name: String) {
        try {
            preferences.delete(name)
        } catch (error: Exception) {
            StorageNotice.report("Couldn’t save settings", error)
        }
    }
}
