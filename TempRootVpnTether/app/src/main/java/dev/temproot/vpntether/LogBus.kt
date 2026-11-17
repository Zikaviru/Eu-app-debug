package dev.temproot.vpntether

import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.*

object LogBus {
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun log(msg: String) {
        val t = fmt.format(Date())
        val new = msg.lines().map { "[$t] $it" }
        _lines.update { (it + new).takeLast(600) }
    }
    fun clear() { _lines.value = emptyList() }
}
