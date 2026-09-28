package com.openwrtmanager.mobile.update

import com.openwrtmanager.mobile.BuildConfig
import com.openwrtmanager.mobile.model.ReleaseInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    private const val LATEST_RELEASE =
        "https://api.github.com/repos/sockc/OpenWrtManager/releases/latest"

    suspend fun check(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val conn = (URL(LATEST_RELEASE).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "OpenWrtManager/${BuildConfig.VERSION_NAME}")
        }

        try {
            if (conn.responseCode !in 200..299) return@withContext null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val tag = root.optString("tag_name")
            val version = tag.removePrefix("v")
            val assets = root.optJSONArray("assets")
            var apk = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val item = assets.optJSONObject(i) ?: continue
                    val name = item.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apk = item.optString("browser_download_url")
                        break
                    }
                }
            }
            ReleaseInfo(
                tagName = tag,
                versionName = version,
                body = root.optString("body"),
                apkUrl = apk,
                htmlUrl = root.optString("html_url")
            )
        } finally {
            conn.disconnect()
        }
    }

    fun isNewer(latest: String, current: String = BuildConfig.VERSION_NAME): Boolean {
        fun parts(v: String): List<Int> =
            v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(latest)
        val b = parts(current)
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av > bv
        }
        return false
    }
}
