package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.server.SafeTextFile
import com.mineserve.mobile.ui.McViewModel.TextEditorFile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class TextEditorDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val fileManagerRoot: () -> File,
    private val refreshFiles: () -> Unit,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    private val _textEditorFile = MutableStateFlow<TextEditorFile?>(null)
    val textEditorFile: StateFlow<TextEditorFile?> = _textEditorFile.asStateFlow()

    fun openTextFile(file: java.io.File) = scope.launch {
        try {
            val content = withContext(Dispatchers.IO) { SafeTextFile.read(fileManagerRoot(), file) }
            _textEditorFile.value = TextEditorFile(content.file.absolutePath, content.file.name, content.text)
        } catch (e: Exception) { reportError(e.message ?: str(R.string.err_open_text)) }
    }
    fun closeTextFile() { _textEditorFile.value = null }
    fun saveTextFile(path: String, text: String) = scope.launch {
        try {
            withContext(Dispatchers.IO) { SafeTextFile.write(fileManagerRoot(), java.io.File(path), text) }
            _textEditorFile.value = TextEditorFile(path, java.io.File(path).name, text)
            reportMessage(str(R.string.msg_file_saved))
            refreshFiles()
        } catch (e: Exception) { reportError(e.message ?: str(R.string.err_save_text)) }
    }
    fun canEditTextFile(file: java.io.File) = file.isFile && SafeTextFile.isSupported(file)

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
