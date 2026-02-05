package com.example.downloaderforkids

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FormatItem(
    val formatId: String,
    val container: String,
    val codec: String,
    val resolution: String,
    val fileSize: String
) {
    override fun toString(): String {
        return "[$formatId] $container ($codec) - $resolution ($fileSize)"
    }
}

class MainActivity : AppCompatActivity() {

    private var saveUri: Uri? = null
    private lateinit var dirPickerLauncher: ActivityResultLauncher<Uri?>
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>

    private lateinit var urlInput: EditText
    private lateinit var selectLocationButton: Button
    private lateinit var locationTextView: TextView
    private lateinit var btnDownload: Button
    private lateinit var btnUpdate: Button
    private lateinit var txtStatus: TextView

    // Broadcast Receiver 정의
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            when (intent.action) {
                DownloadService.ACTION_DOWNLOAD_PROGRESS -> {
                    val message = intent.getStringExtra(DownloadService.EXTRA_STATUS_MESSAGE)
                    // val progress = intent.getIntExtra(DownloadService.EXTRA_PROGRESS, 0) // 필요 시 사용
                    txtStatus.text = message
                    btnDownload.isEnabled = false // 다운로드 중 버튼 비활성화 유지
                }
                DownloadService.ACTION_DOWNLOAD_COMPLETE -> {
                    val message = intent.getStringExtra(DownloadService.EXTRA_STATUS_MESSAGE)
                    txtStatus.text = message
                    btnDownload.isEnabled = true // 완료 시 버튼 활성화
                    urlInput.text.clear()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        try {
            YoutubeDL.getInstance().init(applicationContext)
            FFmpeg.getInstance().init(applicationContext)
        } catch (e: Exception) {
            Toast.makeText(this, "초기화 실패", Toast.LENGTH_SHORT).show()
        }

        urlInput = findViewById(R.id.urlEditText)
        selectLocationButton = findViewById(R.id.selectLocationButton)
        locationTextView = findViewById(R.id.locationTextView)
        btnDownload = findViewById(R.id.downloadButton)
        btnUpdate = findViewById(R.id.updateButton)
        txtStatus = findViewById(R.id.statusTextView)

        val sharedPref = getSharedPreferences("DownloaderPrefs", Context.MODE_PRIVATE)
        val savedUriString = sharedPref.getString("save_uri", null)
        if (savedUriString != null) {
            try {
                val uri = Uri.parse(savedUriString)
                saveUri = uri
                val docFile = DocumentFile.fromTreeUri(this, uri)
                locationTextView.text = "저장위치: ${docFile?.name}"
            } catch (e: Exception) {
                // Ignore if URI is invalid
            }
        }

        dirPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                saveUri = uri
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                val docFile = DocumentFile.fromTreeUri(this, uri)
                locationTextView.text = "저장위치: ${docFile?.name}"

                with(sharedPref.edit()) {
                    putString("save_uri", uri.toString())
                    apply()
                }
            }
        }

        requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Toast.makeText(this, "알림 권한 허용됨", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "알림 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            }
        }

        setupListeners()
        handleSharedIntent(intent)
        checkNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        // Broadcast Receiver 등록
        val filter = IntentFilter().apply {
            addAction(DownloadService.ACTION_DOWNLOAD_PROGRESS)
            addAction(DownloadService.ACTION_DOWNLOAD_COMPLETE)
        }
        ContextCompat.registerReceiver(this, downloadReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        super.onStop()
        // Broadcast Receiver 해제
        try {
            unregisterReceiver(downloadReceiver)
        } catch (e: IllegalArgumentException) {
            // 이미 해제된 경우 무시
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
    }

    private fun handleSharedIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (sharedText != null) {
                val extractedUrl = extractUrl(sharedText)
                if (extractedUrl != null) {
                    urlInput.setText(extractedUrl)
                } else {
                    urlInput.setText(sharedText)
                }
            }
        }
    }

    private fun extractUrl(text: String): String? {
        val regex = "(https?://\\S+)".toRegex()
        val matchResult = regex.find(text)
        return matchResult?.value
    }

    private fun setupListeners() {
        selectLocationButton.setOnClickListener { dirPickerLauncher.launch(null) }

        btnDownload.setOnClickListener {
            if (saveUri == null) {
                Toast.makeText(this, "저장 위치를 먼저 선택하세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val url = urlInput.text.toString()
            if (url.isBlank()) return@setOnClickListener

            fetchFormatsAndShowDialog(url)
        }

        btnUpdate.setOnClickListener { updateLibrary() }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun fetchFormatsAndShowDialog(url: String) {
        txtStatus.text = "포맷 목록 분석 중..."
        btnDownload.isEnabled = false

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val videoInfo = YoutubeDL.getInstance().getInfo(YoutubeDLRequest(url))
                val formats = videoInfo.formats

                if (formats == null) throw Exception("포맷 정보를 찾을 수 없습니다.")

                val videoList = formats.filter {
                    it.vcodec != "none" && (it.acodec == "none" || it.acodec == null)
                }.sortedByDescending { it.height }

                val audioList = formats.filter {
                    it.acodec != "none" && (it.vcodec == "none" || it.vcodec == null)
                }.sortedByDescending { it.fileSize }

                withContext(Dispatchers.Main) {
                    showSelectionDialog(url, videoList, audioList)
                    txtStatus.text = "옵션을 선택하세요."
                    btnDownload.isEnabled = true
                }

            } catch (e: Exception) {
                Log.e("YTDL", "분석 실패", e)
                withContext(Dispatchers.Main) {
                    txtStatus.text = "분석 실패: ${e.message}"
                    btnDownload.isEnabled = true
                }
            }
        }
    }

    private fun showSelectionDialog(url: String, videos: List<VideoFormat>, audios: List<VideoFormat>) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_select_format, null)
        val spinnerVideo = dialogView.findViewById<Spinner>(R.id.spinnerVideo)
        val spinnerAudio = dialogView.findViewById<Spinner>(R.id.spinnerAudio)

        val videoItems = mutableListOf<FormatItem>()
        videoItems.add(FormatItem("none", "None", "None", "비디오 없음 (오디오만)", "N/A"))
        videoItems.addAll(videos.map {
            FormatItem(
                formatId = it.formatId ?: "",
                container = it.ext ?: "mp4",
                codec = it.vcodec ?: "unknown",
                resolution = "${it.width}x${it.height}",
                fileSize = formatFileSize(it.fileSize)
            )
        })

        val audioItems = audios.map {
            FormatItem(
                formatId = it.formatId ?: "",
                container = it.ext ?: "m4a",
                codec = it.acodec ?: "unknown",
                resolution = "Audio",
                fileSize = formatFileSize(it.fileSize)
            )
        }

        spinnerVideo.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, videoItems)
        spinnerAudio.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, audioItems)

        AlertDialog.Builder(this)
            .setTitle("다운로드 옵션 선택")
            .setView(dialogView)
            .setPositiveButton("다운로드") { _, _ ->
                val selectedVideo = spinnerVideo.selectedItem as? FormatItem
                val selectedAudio = spinnerAudio.selectedItem as? FormatItem

                if (selectedVideo != null && selectedAudio != null) {
                    startDownloadService(url, selectedVideo.formatId, selectedAudio.formatId)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun startDownloadService(url: String, videoId: String, audioId: String) {
        val intent = Intent(this, DownloadService::class.java).apply {
            putExtra(DownloadService.EXTRA_URL, url)
            putExtra(DownloadService.EXTRA_VIDEO_ID, videoId)
            putExtra(DownloadService.EXTRA_AUDIO_ID, audioId)
            putExtra(DownloadService.EXTRA_SAVE_URI, saveUri.toString())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "백그라운드에서 다운로드가 시작됩니다.", Toast.LENGTH_SHORT).show()
        urlInput.text.clear()
        // txtStatus.text는 BroadcastReceiver가 업데이트하므로 여기서는 간단히 설정하거나 생략 가능
        btnDownload.isEnabled = false // 중복 클릭 방지
    }

    private fun updateLibrary() {
        Toast.makeText(this, "업데이트 확인 중...", Toast.LENGTH_SHORT).show()
        btnUpdate.isEnabled = false
        CoroutineScope(Dispatchers.IO).launch {
            try {
                YoutubeDL.getInstance().updateYoutubeDL(applicationContext)
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "라이브러리 업데이트 완료", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "업데이트 실패", Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    btnUpdate.isEnabled = true
                }
            }
        }
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "N/A"
        val mb = size / (1024.0 * 1024.0)
        return String.format("%.1f MB", mb)
    }
}