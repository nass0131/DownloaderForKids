package com.example.downloaderforkids

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class YoutubeRepository {

    suspend fun fetchVideoInfo(url: String): Result<VideoInfo> {
        return withContext(Dispatchers.IO) {
            try {
                val request = YoutubeDLRequest(url)
                val info = YoutubeDL.getInstance().getInfo(request)
                Result.success(info)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun updateLibrary(context: Context): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().updateYoutubeDL(context)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
