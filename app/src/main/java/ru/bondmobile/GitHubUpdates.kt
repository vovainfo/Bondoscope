package ru.bondmobile

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

private const val REPOSITORY = "vovainfo/Bondoscope"
private const val MAX_APK_BYTES = 100L * 1024 * 1024
private val VERSION = Regex("""(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""")
private val SHA256 = Regex("[a-f0-9]{64}")
private val DOWNLOAD_HOSTS = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")

internal data class UpdateRelease(val version: String, val url: String, val size: Long, val sha256: String)

internal class UpdateException(message: String) : IOException(message)

internal fun parseLatestRelease(json: JSONObject, installedVersion: String): UpdateRelease? {
    if (json.getBoolean("draft") || json.getBoolean("prerelease")) return null
    val tag = json.getString("tag_name")
    val version = tag.removePrefix("v")
    val latest = AppVersion.parse(version)
    if (latest <= AppVersion.parse(installedVersion)) return null

    val filename = "Bondoscope-$version.apk"
    val url = "https://github.com/$REPOSITORY/releases/download/$tag/$filename"
    val assets = json.getJSONArray("assets")
    val asset = (0 until assets.length()).asSequence().map(assets::getJSONObject).firstOrNull {
        it.optString("name") == filename && it.optString("state") == "uploaded"
    } ?: throw UpdateException("В релизе $version нет APK для установки")
    val size = asset.optLong("size")
    val digest = asset.optString("digest")
    val sha256 = digest.removePrefix("sha256:")
    if (asset.optString("browser_download_url") != url || size !in 1..MAX_APK_BYTES ||
        !digest.startsWith("sha256:") || !SHA256.matches(sha256)) {
        throw UpdateException("Не удалось подтвердить данные APK для версии $version")
    }
    return UpdateRelease(version, url, size, sha256)
}

private data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion) = compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    companion object {
        fun parse(version: String): AppVersion {
            val parts = VERSION.matchEntire(version)?.groupValues?.drop(1)?.mapNotNull(String::toIntOrNull)
            if (parts?.size != 3) throw UpdateException("Не удалось распознать номер версии $version")
            return AppVersion(parts[0], parts[1], parts[2])
        }
    }
}

internal class GitHubUpdates(private val context: Context) {
    private val packageManager = context.packageManager
    private val installed = packageManager.getPackageInfo(context.packageName, 0)
    val installedVersion = installed.versionName.orEmpty()

    fun latest(): UpdateRelease? {
        val url = URI("https://api.github.com/repos/$REPOSITORY/releases/latest").toURL()
        val connection = connect(url)
        try {
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            when (val status = connection.responseCode) {
                200 -> {
                    val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
                    return parseLatestRelease(json, installedVersion)
                }
                403, 429 -> throw UpdateException("GitHub ограничил число запросов. Повторите проверку позже.")
                else -> throw UpdateException("Не удалось проверить релиз GitHub (HTTP $status)")
            }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(release: UpdateRelease, onProgress: (Int) -> Unit): File {
        val folder = File(context.cacheDir, "updates")
        if (!folder.mkdirs() && !folder.isDirectory) throw IOException("Update cache is unavailable")
        val temporary = File.createTempFile("download-", ".apk", folder)
        try {
            val connection = openApk(URI(release.url).toURL())
            try {
                saveVerifiedApk(connection, release, temporary, onProgress)
            } finally {
                connection.disconnect()
            }

            val candidate = packageManager.getPackageArchiveInfo(temporary.path, 0)
            if (candidate == null || candidate.packageName != context.packageName ||
                candidate.versionName != release.version ||
                PackageInfoCompat.getLongVersionCode(candidate) <= PackageInfoCompat.getLongVersionCode(installed)) {
                throw UpdateException("Загруженный APK не подходит для обновления этой версии Бондоскопа")
            }
            currentCoroutineContext().ensureActive()
            val apk = File(folder, "Bondoscope-${release.version}.apk")
            if (!temporary.renameTo(apk)) throw IOException("Cannot save verified APK")
            folder.listFiles()?.filter { it != apk }?.forEach { it.delete() }
            return apk
        } finally {
            temporary.delete()
        }
    }

    private suspend fun saveVerifiedApk(
        connection: HttpsURLConnection, release: UpdateRelease, file: File, onProgress: (Int) -> Unit
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        connection.inputStream.use { input ->
            file.outputStream().buffered().use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    received += count
                    if (received > release.size) throw UpdateException("Размер APK не совпадает с данными релиза")
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    onProgress((received * 100 / release.size).toInt())
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (received != release.size || actual != release.sha256) {
            throw UpdateException("Не удалось проверить целостность APK. Скачайте обновление ещё раз.")
        }
    }

    private suspend fun openApk(initialUrl: URL): HttpsURLConnection {
        var url = initialUrl
        repeat(5) {
            currentCoroutineContext().ensureActive()
            if (url.protocol != "https" || url.host !in DOWNLOAD_HOSTS || url.userInfo != null || url.port !in setOf(-1, 443)) {
                throw UpdateException("GitHub вернул неподдерживаемый адрес скачивания APK")
            }
            val connection = connect(url)
            var downloadOpen = false
            try {
                val status = connection.responseCode
                if (status == 200) {
                    downloadOpen = true
                    return connection
                }
                val location = connection.getHeaderField("Location")
                if (status !in 300..399 || location.isNullOrBlank()) {
                    throw UpdateException("Не удалось скачать APK с GitHub (HTTP $status)")
                }
                url = url.toURI().resolve(location).toURL()
            } finally {
                if (!downloadOpen) connection.disconnect()
            }
        }
        throw UpdateException("Слишком много переадресаций при скачивании APK")
    }

    private fun connect(url: URL) = (url.openConnection() as HttpsURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 20_000
        instanceFollowRedirects = false
        setRequestProperty("User-Agent", "Bondoscope/$installedVersion")
    }
}
