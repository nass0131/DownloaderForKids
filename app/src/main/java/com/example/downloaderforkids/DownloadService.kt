package com.example.downloaderforkids

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException

class DownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "DownloadChannel"
        const val NOTIFICATION_ID = 1
        const val EXTRA_URL = "extra_url"
        const val EXTRA_VIDEO_ID = "extra_video_id"
        const val EXTRA_AUDIO_ID = "extra_audio_id"
        const val EXTRA_SAVE_URI = "extra_save_uri"

        // Broadcast Constants
        const val ACTION_DOWNLOAD_PROGRESS = "com.example.downloaderforkids.DOWNLOAD_PROGRESS"
        const val ACTION_DOWNLOAD_COMPLETE = "com.example.downloaderforkids.DOWNLOAD_COMPLETE"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_STATUS_MESSAGE = "extra_status_message"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        val videoId = intent?.getStringExtra(EXTRA_VIDEO_ID)
        val audioId = intent?.getStringExtra(EXTRA_AUDIO_ID)
        val saveUriString = intent?.getStringExtra(EXTRA_SAVE_URI)

        if (url != null && videoId != null && audioId != null && saveUriString != null) {
            val saveUri = Uri.parse(saveUriString)
            startForeground(NOTIFICATION_ID, createNotification("다운로드 준비 중...", 0, true))
            startDownload(url, videoId, audioId, saveUri)
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startDownload(url: String, videoId: String, audioId: String, saveUri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            val tempDirName = "temp_dl_${System.currentTimeMillis()}"
            val tempDir = File(externalCacheDir, tempDirName)
            var fileName = "video.mp4"

            try {
                if (!tempDir.exists()) tempDir.mkdirs()

                val request = YoutubeDLRequest(url)
                request.addOption("-f", "$videoId+$audioId")
                request.addOption("-o", "${tempDir.absolutePath}/%(title)s.%(ext)s")
                request.addOption("--force-overwrites")

                YoutubeDL.getInstance().execute(request) { progress, eta, _ ->
                    val progressText = "$progress% (남은 시간: $eta 초)"
                    updateNotification("다운로드 중... $progressText", progress.toInt(), true)
                    
                    // Broadcast Progress (패키지명 명시)
                    val intent = Intent(ACTION_DOWNLOAD_PROGRESS).apply {
                        putExtra(EXTRA_PROGRESS, progress.toInt())
                        putExtra(EXTRA_STATUS_MESSAGE, "다운로드 중... $progressText")
                        setPackage(packageName) // 이 부분이 중요합니다
                    }
                    sendBroadcast(intent)
                }

                val downloadedFile = tempDir.listFiles()?.firstOrNull()
                    ?: throw IOException("파일 생성 실패")
                fileName = downloadedFile.name

                // 파일 저장 로직
                val targetDir = DocumentFile.fromTreeUri(applicationContext, saveUri)!!
                val destFile = targetDir.findFile(fileName)?.let {
                    it.delete()
                    targetDir.createFile("video/mp4", fileName)
                } ?: targetDir.createFile("video/mp4", fileName)

                if (destFile == null) throw IOException("저장 실패")

                contentResolver.openOutputStream(destFile.uri)?.use { output ->
                    FileInputStream(downloadedFile).use { input ->
                        input.copyTo(output)
                    }
                }

                withContext(Dispatchers.Main) {
                    showCompletionNotification("다운로드 완료", "$fileName 저장됨")
                    
                    // Broadcast Completion (패키지명 명시)
                    val intent = Intent(ACTION_DOWNLOAD_COMPLETE).apply {
                        putExtra(EXTRA_STATUS_MESSAGE, "완료! ($fileName)")
                        setPackage(packageName)
                    }
                    sendBroadcast(intent)
                }

            } catch (e: Exception) {
                Log.e("DownloadService", "Error", e)
                withContext(Dispatchers.Main) {
                    showCompletionNotification("다운로드 실패", e.message ?: "알 수 없는 오류")
                    
                    // Broadcast Failure (패키지명 명시)
                    val intent = Intent(ACTION_DOWNLOAD_COMPLETE).apply {
                        putExtra(EXTRA_STATUS_MESSAGE, "에러: ${e.message}")
                        setPackage(packageName)
                    }
                    sendBroadcast(intent)
                }
            } finally {
                tempDir.deleteRecursively()
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String, progress: Int, ongoing: Boolean): android.app.Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("비디오 다운로더")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .setContentIntent(pendingIntent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true) // 알림 소리 반복 방지
            .build()
    }

    private fun updateNotification(content: String, progress: Int, ongoing: Boolean) {
        val notification = createNotification(content, progress, ongoing)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun showCompletionNotification(title: String, content: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true) // 터치 시 사라짐
            .build()
        
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID + 1, notification) // 진행 중 알림과 다른 ID 사용
    }
}