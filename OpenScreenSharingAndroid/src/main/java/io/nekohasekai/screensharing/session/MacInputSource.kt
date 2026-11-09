package io.nekohasekai.screensharing.session

data class MacInputSource(val id: String) {
    val isInputMethod: Boolean get() = !id.startsWith("com.apple.keylayout.")
}
