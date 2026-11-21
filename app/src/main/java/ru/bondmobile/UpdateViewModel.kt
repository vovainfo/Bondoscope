package ru.bondmobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object Current : UpdateState
    data class Available(val release: UpdateRelease) : UpdateState
    data class Downloading(val release: UpdateRelease, val percent: Int) : UpdateState
    data class Ready(val release: UpdateRelease, val apk: File) : UpdateState
    data class Failed(val message: String, val release: UpdateRelease? = null) : UpdateState
}

internal class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val github = GitHubUpdates(app)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = _state.asStateFlow()
    val installedVersion = github.installedVersion

    fun check() {
        if (_state.value == UpdateState.Checking || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        viewModelScope.launch {
            try {
                val latest = withContext(Dispatchers.IO) { github.latest() }
                _state.value = latest?.let { UpdateState.Available(it) } ?: UpdateState.Current
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = UpdateState.Failed(
                    error.updateMessage("Не удалось проверить обновления. Проверьте подключение и повторите попытку.")
                )
            }
        }
    }

    fun download() {
        val release = when (val current = _state.value) {
            is UpdateState.Available -> current.release
            is UpdateState.Failed -> current.release
            else -> null
        } ?: return
        _state.value = UpdateState.Downloading(release, 0)
        viewModelScope.launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    github.download(release) { percent -> _state.value = UpdateState.Downloading(release, percent) }
                }
                _state.value = UpdateState.Ready(release, apk)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = UpdateState.Failed(
                    error.updateMessage("Не удалось скачать обновление. Проверьте подключение и свободное место."), release
                )
            }
        }
    }

    fun apkMissing(ready: UpdateState.Ready) {
        if (_state.value == ready) _state.value = UpdateState.Failed("Файл обновления нужно скачать заново", ready.release)
    }

    private fun Exception.updateMessage(fallback: String) = (this as? UpdateException)?.message ?: fallback
}
