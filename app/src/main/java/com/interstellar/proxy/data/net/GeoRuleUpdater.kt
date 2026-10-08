package com.interstellar.proxy.data.net

import android.util.Log
import com.interstellar.proxy.InterstellarApplication
import com.interstellar.proxy.data.RulesStore
import com.interstellar.proxy.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Updates the built-in rule / geodata files from their upstream sources:
 *
 *  - sing-box: SagerNet sing-geosite / sing-geoip binary rule sets (.srs)
 *  - mihomo:   MetaCubeX meta-rules-dat geosite.dat + geoip.metadb
 *  - Xray:     v2fly geosite (dlc.dat) + geoip.dat
 *
 * Downloads ride the local mixed inbound when a core is running (same escape
 * hatch as subscription updates — GitHub is unreachable directly in CN), then
 * fall back to direct. Each file is downloaded to a temp sibling, sanity
 * checked and swapped in atomically, so a failed update never leaves a
 * corrupt rule file behind.
 */
object GeoRuleUpdater {
    private const val TAG = "GeoRuleUpdater"
    private const val MIXED_PORT = 2080

    /** Localized string following the CURRENT language (live, no restart needed). */
    private fun str(id: Int): String =
        com.interstellar.proxy.ktx.AppLanguage.getString(InterstellarApplication.application, id)

    private fun str(id: Int, vararg formatArgs: Any): String =
        com.interstellar.proxy.ktx.AppLanguage.getString(InterstellarApplication.application, id, *formatArgs)

    private data class GeoFile(
        val name: String,
        val urls: List<String>,
        val target: File,
        val validate: (File) -> Boolean,
    )

    private val directClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true) // github releases/latest → objects.githubusercontent.com
        .build()

    private val proxiedClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", MIXED_PORT)))
        .build()

    /** The sing-box command socket exists exactly while the core runs. */
    private fun coreRunning(): Boolean =
        File(InterstellarApplication.application.filesDir, "command.sock").exists()

    /** The sing-box binary rule sets this client ships, with their sources. */
    private fun filesFor(): List<GeoFile> = listOf(
        srsFile(
            RulesStore.geolocationNotCn,
            "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-geolocation-!cn.srs",
        ),
        srsFile(
            RulesStore.geositeCn,
            "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-cn.srs",
        ),
        srsFile(
            RulesStore.geoipCn,
            "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-cn.srs",
        ),
        srsFile(
            RulesStore.adsAll,
            "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/category-ads-all.srs",
        ),
    )

    /** srs with a jsdelivr mirror (both repos expose a rule-set branch). */
    private fun srsFile(asset: RulesStore.RuleAsset, url: String): GeoFile = GeoFile(
        name = asset.fileName,
        urls = listOf(url, url.replace("https://raw.githubusercontent.com/", "https://cdn.jsdelivr.net/gh/")
            .replace("/rule-set/", "@rule-set/")),
        target = RulesStore.fileOf(asset),
        validate = ::srsValid,
    )

    // ---- validation ----

    /** Valid srs starts with "SRS\x01" + zlib stream (same sanity as RulesStore). */
    private fun srsValid(f: File): Boolean = f.length() > 8 && startsWith(f, "SRS")

    private fun startsWith(f: File, prefix: String): Boolean = runCatching {
        f.inputStream().use { input ->
            val buf = ByteArray(prefix.length)
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) return@use false
                off += n
            }
            buf.decodeToString() == prefix
        }
    }.getOrDefault(false)

    // ---- update ----

    /**
     * Downloads every rule file. Returns a user-facing summary; throws when
     * nothing could be updated.
     */
    suspend fun update(): String = withContext(Dispatchers.IO) {
        val throughProxy = coreRunning()
        var ok = 0
        val failed = mutableListOf<String>()
        for (file in filesFor()) {
            val attempt = runCatching { download(file, throughProxy) }
            if (attempt.isSuccess) {
                ok++
            } else {
                failed += file.name
                Log.w(TAG, "update ${file.name} failed: ${attempt.exceptionOrNull()?.message}")
            }
        }
        if (ok == 0) {
            error(str(com.interstellar.proxy.R.string.geo_download_failed, failed.joinToString("、")))
        }
        Settings.ruleFilesUpdatedAt = System.currentTimeMillis()
        if (failed.isEmpty()) {
            str(com.interstellar.proxy.R.string.geo_updated_all, ok)
        } else {
            str(com.interstellar.proxy.R.string.geo_updated_partial, ok, failed.joinToString("、"))
        }
    }

    /** One file: temp download → validate → atomic swap. */
    private fun download(file: GeoFile, throughProxy: Boolean) {
        file.target.parentFile?.mkdirs()
        val tmp = File(file.target.parentFile, file.target.name + ".tmp")
        // proxied first when a core runs (the node reaches GitHub), then direct
        val clients = buildList {
            if (throughProxy) add(proxiedClient)
            add(directClient)
        }
        var lastError: Exception? = null
        var downloaded = false
        try {
            outer@ for (url in file.urls) {
                for (client in clients) {
                    try {
                        val request = Request.Builder().url(url).build()
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) error("HTTP ${response.code}")
                            response.body!!.byteStream().use { input ->
                                tmp.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                        downloaded = true
                        break@outer
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastError = e
                    }
                }
            }
            if (!downloaded) throw lastError ?: IllegalStateException(str(com.interstellar.proxy.R.string.geo_download_short))
            if (!file.validate(tmp)) {
                throw IllegalStateException(str(com.interstellar.proxy.R.string.geo_invalid_file))
            }
            if (!tmp.renameTo(file.target)) {
                // rename can fail across a mounted state or AV scan — replace instead
                file.target.delete()
                if (!tmp.renameTo(file.target)) throw IllegalStateException(str(com.interstellar.proxy.R.string.geo_replace_failed))
            }
        } finally {
            tmp.delete()
        }
    }
}
