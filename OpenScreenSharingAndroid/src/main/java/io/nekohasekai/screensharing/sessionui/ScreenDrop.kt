package io.nekohasekai.screensharing.sessionui

import android.provider.DocumentsContract
import android.view.DragAndDropPermissions
import android.view.DragEvent
import androidx.compose.ui.geometry.Offset
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.session.SessionController

class ScreenDrop(
    private val view: RemoteScreenView,
    private val controller: SessionController,
    private val drag: ScreenDrag,
) {
    private val permissions = ArrayList<DragAndDropPermissions>()

    fun onDragEvent(event: DragEvent): Boolean {
        val local = event.localState
        if (local != null) {
            if (!drag.owns(local)) return false
            when (event.action) {
                DragEvent.ACTION_DRAG_LOCATION -> drag.moved(Offset(event.x, event.y))
                DragEvent.ACTION_DRAG_EXITED -> drag.exited()
                DragEvent.ACTION_DROP -> return drag.drop(Offset(event.x, event.y))
                DragEvent.ACTION_DRAG_ENDED -> drag.ended(event.result)
            }
            return true
        }
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                val description = event.clipDescription ?: return false
                return controller.canTransferFiles && description.mimeTypeCount > 0 &&
                    !description.hasMimeType(DocumentsContract.Document.MIME_TYPE_DIR)
            }
            DragEvent.ACTION_DRAG_ENTERED -> {
                val name = event.clipDescription?.label?.toString()?.takeIf { it.isNotBlank() } ?: view.context.getString(R.string.session_item)
                controller.dropEntered(listOf(name))
            }
            DragEvent.ACTION_DRAG_LOCATION -> controller.dropMoved(view.surface.imagePoint(Offset(event.x, event.y)))
            DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> controller.dropExited()
            DragEvent.ACTION_DROP -> {
                val clip = event.clipData
                val uris = if (controller.dropTarget == null || clip == null) emptyList() else (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                if (uris.isEmpty()) {
                    controller.dropExited()
                    return false
                }
                view.context.findActivity()?.requestDragAndDropPermissions(event)?.let(permissions::add)
                controller.drop(uris)
            }
        }
        return true
    }
}
