@file:OptIn(ExperimentalPathApi::class)

package io.nekohasekai.screensharing.model

import android.system.ErrnoException
import android.system.Os
import io.nekohasekai.screensharing.App
import java.io.File
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

object AppDirectory {
    val caches: File get() = App.instance.cacheDir

    fun cacheSize(): Long = caches.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.filter { it.isFile }.sumOf { file ->
        try {
            Os.lstat(file.path).st_blocks * 512
        } catch (error: ErrnoException) {
            0L
        }
    }

    fun clearCaches() {
        val items = caches.listFiles() ?: throw FileSystemException(caches)
        for (item in items) {
            item.toPath().deleteRecursively()
        }
    }
}
