package ru.bondmobile

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun UpdatePage(model: UpdateViewModel) {
    val context = LocalContext.current
    val state by model.state.collectAsStateWithLifecycle()
    var showPermission by rememberSaveable { mutableStateOf(false) }
    var installError by rememberSaveable { mutableStateOf<String?>(null) }

    fun openInstaller() {
        val ready = model.state.value as? UpdateState.Ready ?: return
        if (!ready.apk.isFile) {
            model.apkMissing(ready)
            return
        }

        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", ready.apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                clipData = ClipData.newRawUri(ready.apk.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        }.onFailure { installError = "Не удалось открыть установщик Android" }
    }

    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) openInstaller()
    }

    PageList {
        item { PageHeader("Обновления", "Установленная версия ${model.installedVersion}") }
        item {
            Sheet {
                when (val current = state) {
                    UpdateState.Idle -> {
                        Text("Проверьте, вышла ли новая версия на GitHub", color = Muted)
                        Button(onClick = model::check) { Text("Проверить обновления") }
                    }
                    UpdateState.Checking -> {
                        Text("Проверяем релизы GitHub…")
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    UpdateState.Current -> {
                        Text("У вас актуальная версия")
                        Button(onClick = model::check) { Text("Проверить ещё раз") }
                    }
                    is UpdateState.Available -> {
                        Text("Доступна версия ${current.release.version}", style = MaterialTheme.typography.titleLarge)
                        Button(onClick = model::download) { Text("Скачать обновление") }
                    }
                    is UpdateState.Downloading -> {
                        Text("Скачиваем версию ${current.release.version} · ${current.percent}%")
                        LinearProgressIndicator(progress = { current.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                    is UpdateState.Ready -> {
                        Text("Версия ${current.release.version} готова к установке", style = MaterialTheme.typography.titleLarge)
                        installError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(onClick = {
                            installError = null
                            if (context.packageManager.canRequestPackageInstalls()) openInstaller()
                            else showPermission = true
                        }) { Text("Установить обновление") }
                    }
                    is UpdateState.Failed -> {
                        Text(current.message, color = MaterialTheme.colorScheme.error)
                        if (current.release != null) {
                            Button(onClick = model::download) { Text("Повторить скачивание") }
                            TextButton(onClick = model::check) { Text("Проверить обновления") }
                        } else {
                            Button(onClick = model::check) { Text("Повторить проверку") }
                        }
                    }
                }
            }
        }
    }

    if (showPermission) {
        AlertDialog(
            onDismissRequest = { showPermission = false },
            title = { Text("Разрешить установку обновлений?") },
            text = { Text("В настройках Android включите «Разрешить установку из этого источника» для Бондоскопа и вернитесь в приложение.") },
            confirmButton = {
                TextButton(onClick = {
                    showPermission = false
                    runCatching {
                        val app = Uri.fromParts("package", context.packageName, null)
                        requestPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, app))
                    }.onFailure { installError = "Не удалось открыть настройки установки Android" }
                }) { Text("Открыть настройки") }
            },
            dismissButton = { TextButton(onClick = { showPermission = false }) { Text("Отмена") } }
        )
    }
}
