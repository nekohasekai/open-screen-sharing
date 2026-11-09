package io.nekohasekai.screensharing.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.nekohasekai.screensharing.App
import io.nekohasekai.screensharing.app.StorageNotice
import io.nekohasekai.screensharing.session.DeviceInfo
import io.nekohasekai.screensharing.session.SessionMemory
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.Callable

data class StoredComputer(
    val username: String,
    val password: String?,
    val memory: SessionMemory,
    val networkConfiguration: NetworkConfiguration,
    val device: DeviceInfo?,
    val usesHighPerformance: Boolean,
    val deviceType: DeviceType?,
    val keyboardSources: List<String>,
    val trustedFingerprint: String,
)

sealed interface PasswordUpdate {
    data object Keep : PasswordUpdate
    data class Save(val password: String) : PasswordUpdate
    data object Delete : PasswordUpdate

    val isPending: Boolean get() = this != Keep
}

data class ConnectionRecord(
    val computerID: UUID?,
    val name: String,
    val address: ComputerAddress,
    val username: String,
    val password: PasswordUpdate,
    val remoteProtocol: RemoteProtocol,
    val usesHighPerformance: Boolean,
    val memory: SessionMemory,
    val device: DeviceInfo?,
)

object ComputerStore {
    private val dao get() = AppDatabase.shared.computers()

    var computers: List<Computer> by mutableStateOf(
        try {
            dao.all()
        } catch (error: Exception) {
            StorageNotice.report("Couldn’t load connections", error)
            emptyList()
        },
    )
        private set

    init {
        App.scope.launch {
            dao.observeAll()
                .catch { StorageNotice.report("Couldn’t load connections", it) }
                .collect { computers = it }
        }
    }

    fun computer(id: UUID): Computer? = try {
        dao.get(id)
    } catch (error: Exception) {
        StorageNotice.report("Couldn’t load connections", error)
        null
    }

    fun stored(id: UUID): StoredComputer? {
        val computer = computer(id) ?: return null
        return StoredComputer(
            username = computer.username,
            password = if (computer.authenticates) computer.password else null,
            memory = computer.memory,
            networkConfiguration = computer.networkConfiguration,
            device = if (computer.model.isEmpty()) null else DeviceInfo(computer.model, computer.enclosureColor),
            usesHighPerformance = computer.usesHighPerformance,
            deviceType = if (computer.usesHighPerformance) computer.deviceType else null,
            keyboardSources = computer.keyboardSources,
            trustedFingerprint = computer.trustedFingerprint,
        )
    }

    fun computer(address: ComputerAddress, remoteProtocol: RemoteProtocol, username: String?): Computer? = try {
        matching(dao.all(), address, remoteProtocol, username)
    } catch (error: Exception) {
        StorageNotice.report("Couldn’t load connections", error)
        null
    }

    private fun matching(computers: List<Computer>, address: ComputerAddress, remoteProtocol: RemoteProtocol, username: String?): Computer? {
        val candidates = computers.filter { it.remoteProtocol == remoteProtocol && it.address.matches(address) }
        if (username == null) return candidates.firstOrNull()
        return candidates.firstOrNull { it.username == username } ?: candidates.firstOrNull { it.username.isEmpty() }
    }

    fun save(computer: Computer, modify: (Computer) -> Computer): Computer = AppDatabase.shared.runInTransaction(
        Callable {
            val saved = modify(dao.get(computer.id) ?: computer)
            dao.save(saved)
            saved
        },
    )

    fun delete(id: UUID) {
        dao.delete(id)
    }

    fun record(connection: ConnectionRecord): UUID {
        val username = connection.username.ifEmpty { null }
        return AppDatabase.shared.runInTransaction(
            Callable {
                var computer = connection.computerID?.let { dao.get(it) }
                    ?: matching(dao.all(), connection.address, connection.remoteProtocol, username)
                    ?: Computer(connection.name, connection.address, connection.remoteProtocol).remember(connection.memory)
                if (username != null && computer.username != username) {
                    computer = computer.copy(username = username, password = "", authenticates = false)
                }
                computer = when (val password = connection.password) {
                    PasswordUpdate.Keep -> computer
                    is PasswordUpdate.Save -> computer.copy(password = password.password, authenticates = true)
                    PasswordUpdate.Delete -> computer.copy(password = "", authenticates = false)
                }
                computer = computer.copy(
                    lastConnected = System.currentTimeMillis(),
                    remoteProtocol = connection.remoteProtocol,
                    usesHighPerformance = connection.usesHighPerformance,
                )
                connection.device?.let { computer = computer.apply(it) }
                dao.save(computer)
                computer.id
            },
        )
    }

    fun remember(memory: SessionMemory, id: UUID) {
        update(id) { it.remember(memory) }
    }

    fun setDevice(device: DeviceInfo, id: UUID) {
        update(id) { it.apply(device) }
    }

    fun setKeyboardSources(sources: List<String>, id: UUID) {
        update(id) { it.copy(keyboardSources = sources) }
    }

    fun setTrustedFingerprint(fingerprint: String, id: UUID) {
        update(id) { it.copy(trustedFingerprint = fingerprint) }
    }

    fun setThumbnail(thumbnail: ByteArray, id: UUID) {
        update(id) { it.copy(thumbnail = thumbnail) }
    }

    private fun update(id: UUID, modify: (Computer) -> Computer) {
        AppDatabase.shared.runInTransaction(
            Runnable {
                val computer = dao.get(id) ?: return@Runnable
                val changed = modify(computer)
                if (changed != computer) {
                    dao.save(changed)
                }
            },
        )
    }
}
