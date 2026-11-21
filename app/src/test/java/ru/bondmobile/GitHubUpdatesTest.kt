package ru.bondmobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubUpdatesTest {
    private val digest = "ab".repeat(32)
    private val url = "https://github.com/vovainfo/Bondoscope/releases/download/v1.10.0/Bondoscope-1.10.0.apk"

    @Test
    fun selectsTheVersionedApkUsingNumericVersionOrdering() {
        val latest = parseLatestRelease(release(), "1.2.0")
        assertEquals(UpdateRelease("1.10.0", url, 7_000_000L, digest), latest)
    }

    @Test
    fun ignoresInstalledOrOlderVersionsAndUnpublishedReleases() {
        assertNull(parseLatestRelease(release(), "1.10.0"))
        assertNull(parseLatestRelease(release(), "2.0.0"))
        assertNull(parseLatestRelease(release().put("draft", true), "1.2.0"))
        assertNull(parseLatestRelease(release().put("prerelease", true), "1.2.0"))
    }

    @Test
    fun rejectsMissingOrUnverifiedApks() {
        assertThrows(UpdateException::class.java) {
            parseLatestRelease(release().put("assets", JSONArray()), "1.2.0")
        }
        assertThrows(UpdateException::class.java) {
            parseLatestRelease(release(apk().put("digest", JSONObject.NULL)), "1.2.0")
        }
        assertThrows(UpdateException::class.java) {
            parseLatestRelease(release(apk().put("browser_download_url", url.replace("vovainfo", "another-owner"))), "1.2.0")
        }
    }

    private fun release(asset: JSONObject = apk()) = JSONObject()
        .put("tag_name", "v1.10.0")
        .put("draft", false)
        .put("prerelease", false)
        .put("assets", JSONArray().put(JSONObject().put("name", "source.zip")).put(asset))

    private fun apk() = JSONObject()
        .put("name", "Bondoscope-1.10.0.apk")
        .put("state", "uploaded")
        .put("size", 7_000_000L)
        .put("digest", "sha256:$digest")
        .put("browser_download_url", url)
}
