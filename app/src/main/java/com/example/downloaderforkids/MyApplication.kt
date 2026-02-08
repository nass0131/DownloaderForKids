package com.example.downloaderforkids

import android.app.Application
import android.widget.Toast
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)
        } catch (e: Exception) {
            Toast.makeText(this, "라이브러리 초기화 실패: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
