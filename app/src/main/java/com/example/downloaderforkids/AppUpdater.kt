package com.example.downloaderforkids

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val version: String,
    val downloadUrl: String,
    val releaseNotes: String
)

class AppUpdater(private val context: Context) {

    private val owner = "DadHaving2Sons"
    private val repo = "DownloaderForKids"

    sealed class UpdateResult {
        data class Available(val updateInfo: AppUpdateInfo) : UpdateResult()
        data class NoUpdate(val latestVersion: String) : UpdateResult()
        object Error : UpdateResult()
    }

    suspend fun checkForUpdate(currentVersion: String): UpdateResult {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://api.github.com/repos/$owner/$repo/releases/latest")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connect()

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val inputStream = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(inputStream)
                    val tagName = json.getString("tag_name")
                    val body = json.optString("body", "No release notes")
                    val assets = json.getJSONArray("assets")

                    val remoteVersion = tagName.removePrefix("v")
                    val localVersion = currentVersion.removePrefix("v")

                    if (isNewerVersion(remoteVersion, localVersion)) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val downloadUrl = asset.getString("browser_download_url")
                            if (downloadUrl.endsWith(".apk")) {
                                return@withContext UpdateResult.Available(
                                    AppUpdateInfo(tagName, downloadUrl, body)
                                )
                            }
                        }
                    }
                    return@withContext UpdateResult.NoUpdate(tagName)
                }
                UpdateResult.Error
            } catch (e: Exception) {
                e.printStackTrace()
                UpdateResult.Error
            }
        }
    }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        val remoteParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val localParts = local.split(".").map { it.toIntOrNull() ?: 0 }

        for (i in 0 until maxOf(remoteParts.size, localParts.size)) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    suspend fun downloadApk(url: String, onProgress: (Int) -> Unit): File? {
        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connect()
                
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null

                val length = connection.contentLength
                val fileName = "update.apk"
                val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
                
                val input = BufferedInputStream(connection.inputStream)
                val output = FileOutputStream(file)

                val data = ByteArray(1024)
                var total = 0L
                var count: Int
                
                while (input.read(data).also { count = it } != -1) {
                    total += count
                    if (length > 0) {
                        onProgress((total * 100 / length).toInt())
                    }
                    output.write(data, 0, count)
                }

                output.flush()
                output.close()
                input.close()

                file
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    fun installApk(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )
            
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
