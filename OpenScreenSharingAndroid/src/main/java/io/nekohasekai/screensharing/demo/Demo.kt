package io.nekohasekai.screensharing.demo

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.room.Room
import io.nekohasekai.screensharing.App
import io.nekohasekai.screensharing.BuildConfig
import io.nekohasekai.screensharing.model.AppDatabase
import io.nekohasekai.screensharing.model.Computer
import io.nekohasekai.screensharing.model.ComputerAddress
import io.nekohasekai.screensharing.session.PixelBufferImage
import io.nekohasekai.screensharing.session.RemoteSession
import io.nekohasekai.screensharing.session.ScreenPicture
import io.nekohasekai.screensharing.session.SessionController
import io.nekohasekai.screensharing.session.SessionEventSink
import io.nekohasekai.screensharing.session.SessionRoute
import io.nekohasekai.screensharing.session.VirtualDisplayMode
import io.nekohasekai.screensharing.sessionui.SessionActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

class Demo private constructor(
    val page: Page,
    private val controlPort: Int,
) {
    enum class Page(val rawValue: String) {
        Connections("01-Connections"),
        Session("02-Session"),
        ;

        companion object {
            fun of(value: String?): Page? = entries.firstOrNull { it.rawValue == value }
        }
    }

    val database: AppDatabase = Room.inMemoryDatabaseBuilder(App.instance, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    var locale: Locale? by mutableStateOf(null)
        private set

    private val computer: Computer
    private val frameCache = ConcurrentHashMap<String, ByteArray>()
    private val socketLock = Any()
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null
    private var started = false

    init {
        val saved = Computer(
            name = "Mac mini",
            host = "Mac-mini.local",
            port = ComputerAddress.DEFAULT_PORT,
            username = "demo",
            password = "demo",
            authenticates = true,
            model = "Mac16,10",
            lastConnected = System.currentTimeMillis() - 12 * 60 * 1000L,
            createdAt = System.currentTimeMillis(),
            usesHighPerformance = true,
        )
        database.computers().save(saved)
        computer = saved
    }

    fun isAvailable(address: ComputerAddress): Boolean = address == computer.address

    fun startIfNeeded(context: Context) {
        if (started) return
        started = true
        App.scope.launch(Dispatchers.IO) {
            ensureConnected()
            when (page) {
                Page.Connections -> runConnectionsBatch()
                Page.Session -> withContext(Dispatchers.Main) {
                    SessionActivity.start(context, SessionRoute(computer))
                }
            }
        }
    }

    fun makeSession(controller: SessionController, sink: SessionEventSink): RemoteSession {
        if (page == Page.Session) {
            App.scope.launch(AndroidUiDispatcher.Main) {
                snapshotFlow { controller.picture == ScreenPicture.Current }
                    .first { it }
                withFrameNanos { }
                withFrameNanos { }
                delay(200)
                withContext(Dispatchers.IO) {
                    sendLine("shown ${Page.Session.rawValue}")
                }
            }
        }
        return DemoRemoteSession(sink, ::requestFrame)
    }

    private fun ensureConnected() {
        synchronized(socketLock) {
            if (socket != null) return
            val s = Socket()
            s.connect(InetSocketAddress("127.0.0.1", controlPort), 10_000)
            s.tcpNoDelay = true
            socket = s
            input = DataInputStream(BufferedInputStream(s.getInputStream()))
            output = s.getOutputStream()
        }
    }

    private fun sendLine(text: String) {
        synchronized(socketLock) {
            ensureConnected()
            val out = output ?: return
            out.write((text + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    private fun readLine(stream: DataInputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = stream.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8.name())
            if (b == '\n'.code) return buf.toString(Charsets.UTF_8.name()).trimEnd('\r')
            buf.write(b)
        }
    }

    fun requestFrame(mode: VirtualDisplayMode): ByteArray? {
        val name = "${mode.width}x${mode.height}@${mode.density}"
        frameCache[name]?.let { return it }
        return synchronized(socketLock) {
            frameCache[name]?.let { return@synchronized it }
            ensureConnected()
            val out = output ?: return@synchronized null
            val inp = input ?: return@synchronized null
            out.write("frame $name\n".toByteArray(Charsets.UTF_8))
            out.flush()
            val header = readLine(inp) ?: return@synchronized null
            val size = header.toIntOrNull() ?: return@synchronized null
            if (size <= 0) return@synchronized null
            val data = ByteArray(size)
            inp.readFully(data)
            frameCache[name] = data
            data
        }
    }

    private suspend fun runConnectionsBatch() {
        val frame = requestFrame(VirtualDisplayMode(1280, 800, 2))
        val thumbnail = frame?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bitmap ->
                PixelBufferImage.makeThumbnail(bitmap).also { bitmap.recycle() }
            }
        }
        if (thumbnail != null) {
            database.computers().save(computer.copy(thumbnail = thumbnail))
        }
        sendLine("ready")
        val inp = synchronized(socketLock) { input } ?: return
        while (true) {
            val line = synchronized(socketLock) { readLine(inp) } ?: break
            if (line == "quit") {
                exitProcess(0)
            }
            withContext(AndroidUiDispatcher.Main) {
                val target = Locale.forLanguageTag(line)
                Locale.setDefault(target)
                locale = target
                withFrameNanos { }
                withFrameNanos { }
            }
            sendLine("ok")
        }
    }

    companion object {
        @Volatile
        var current: Demo? = null
            private set

        fun configure(intent: Intent): Demo? {
            if (!BuildConfig.DEMO) return null
            current?.let { return it }
            if (!intent.getBooleanExtra("demo", false)) return null
            val page = Page.of(intent.getStringExtra("demo_screenshot")) ?: return null
            val port = intent.getIntExtra("demo_control_port", 0)
            if (port <= 0) return null
            return synchronized(this) {
                current ?: Demo(page, port).also { current = it }
            }
        }
    }
}
