package com.koreainv.dashboard.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val MANDATORY_POLICY_TOKENS = listOf(
    "[mandatory-update]",
    "mandatory-update",
    "update_policy: mandatory",
    "업데이트정책:필수",
    "업데이트 정책: 필수",
    "필수 업데이트",
)

enum class ReleasePolicy {
    MANDATORY,
    RECOMMENDED,
}

data class ReleaseAsset(
    val name: String,
    val url: String,
)

data class ReleaseInfo(
    val tagName: String,
    val body: String,
    val asset: ReleaseAsset,
    val policy: ReleasePolicy,
)

sealed interface InstallPreparationResult {
    data class Ready(val apkFile: File) : InstallPreparationResult
    data object PermissionRequired : InstallPreparationResult
}

class AppUpdateManager {
    companion object {
        private const val RELEASE_REPO = "ianlyoo/koreainv-dashboard"
        private const val RELEASE_API = "https://api.github.com/repos/$RELEASE_REPO/releases/latest"
        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    suspend fun checkForUpdate(context: Context, includeRecommended: Boolean = true): ReleaseInfo? {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(RELEASE_API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "KoreaInvDashboard-Android")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string().orEmpty()
                val json = JsonParser().parse(body).asJsonObject
                parseReleaseInfo(
                    json = json,
                    currentVersion = currentVersionName(context),
                    includeRecommended = includeRecommended,
                )
            }
        }
    }

    suspend fun downloadUpdate(context: Context, release: ReleaseInfo): InstallPreparationResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            return InstallPreparationResult.PermissionRequired
        }

        return withContext(Dispatchers.IO) {
            val updatesDir = File(context.externalCacheDir, "updates").apply { mkdirs() }
            val apkFile = File(updatesDir, release.asset.name)
            val request = Request.Builder()
                .url(release.asset.url)
                .header("User-Agent", "KoreaInvDashboard-Android")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("download_failed_${response.code}")
                apkFile.outputStream().use { output ->
                    response.body?.byteStream()?.copyTo(output)
                }
            }
            InstallPreparationResult.Ready(apkFile)
        }
    }

    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun launchInstaller(context: Context, apkFile: File) {
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            error("installer_not_found")
        }
    }

    private fun currentVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
}

internal fun parseReleaseInfo(
    json: JsonObject,
    currentVersion: String,
    includeRecommended: Boolean,
): ReleaseInfo? {
    val tagName = json.get("tag_name")?.asString?.trim().orEmpty()
    if (!isNewerVersion(tagName, currentVersion)) return null

    val asset = findAndroidAsset(json) ?: return null
    val body = json.get("body")?.asString.orEmpty()
    val policy = parseReleasePolicy(body)
    if (!includeRecommended && policy != ReleasePolicy.MANDATORY) return null

    return ReleaseInfo(
        tagName = tagName,
        body = body,
        asset = asset,
        policy = policy,
    )
}

internal fun parseReleasePolicy(body: String): ReleasePolicy {
    val normalizedBody = body.lowercase()
    val compactBody = normalizedBody.filterNot(Char::isWhitespace)
    val isMandatory = MANDATORY_POLICY_TOKENS.any { token ->
        val normalizedToken = token.lowercase()
        normalizedToken in normalizedBody || normalizedToken.filterNot(Char::isWhitespace) in compactBody
    }

    return if (isMandatory) ReleasePolicy.MANDATORY else ReleasePolicy.RECOMMENDED
}

internal fun isNewerVersion(remote: String, local: String): Boolean {
    fun normalize(version: String): List<Int> = version.removePrefix("v")
        .split('.')
        .map { it.takeWhile(Char::isDigit) }
        .filter { it.isNotBlank() }
        .map { it.toIntOrNull() ?: 0 }

    val remoteParts = normalize(remote)
    val localParts = normalize(local)
    val maxSize = maxOf(remoteParts.size, localParts.size)
    for (i in 0 until maxSize) {
        val r = remoteParts.getOrElse(i) { 0 }
        val l = localParts.getOrElse(i) { 0 }
        if (r != l) return r > l
    }
    return false
}

private fun findAndroidAsset(json: JsonObject): ReleaseAsset? {
    val assets = json.getAsJsonArray("assets") ?: return null
    assets.forEach { element ->
        val asset = element.asJsonObject
        val name = asset.get("name")?.asString.orEmpty()
        val lower = name.lowercase()
        if (lower == "kisdashboard-android.apk" || lower == "app-release.apk") {
            return ReleaseAsset(
                name = name,
                url = asset.get("browser_download_url")?.asString.orEmpty(),
            )
        }
    }
    return null
}
