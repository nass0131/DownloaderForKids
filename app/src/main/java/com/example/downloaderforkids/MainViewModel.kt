package com.example.downloaderforkids

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.mapper.VideoFormat
import kotlinx.coroutines.launch

sealed class UiState {
    object Idle : UiState()
    object Loading : UiState()
    data class Success(val videos: List<VideoFormat>, val audios: List<VideoFormat>) : UiState()
    data class Error(val message: String) : UiState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val repository = YoutubeRepository()
    
    private val _uiState = MutableLiveData<UiState>(UiState.Idle)
    val uiState: LiveData<UiState> = _uiState

    private val _toastMessage = MutableLiveData<String>()
    val toastMessage: LiveData<String> = _toastMessage

    fun fetchFormats(url: String) {
        if (url.isBlank()) return
        
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            val result = repository.fetchVideoInfo(url)
            result.onSuccess { info ->
                val formats = info.formats
                if (formats == null) {
                    _uiState.value = UiState.Error("포맷 정보를 찾을 수 없습니다.")
                    return@onSuccess
                }

                val videoList = formats.filter {
                    it.vcodec != "none" && (it.acodec == "none" || it.acodec == null)
                }.sortedByDescending { it.height }

                val audioList = formats.filter {
                    it.acodec != "none" && (it.vcodec == "none" || it.vcodec == null)
                }.sortedByDescending { it.fileSize }
                
                _uiState.value = UiState.Success(videoList, audioList)
            }.onFailure {
                _uiState.value = UiState.Error("분석 실패: ${it.message}")
            }
        }
    }

    private val _isUpdating = MutableLiveData<Boolean>(false)
    val isUpdating: LiveData<Boolean> = _isUpdating

    init {
        updateLibrary()
    }

    private fun updateLibrary() {
        _isUpdating.value = true
        _toastMessage.value = "라이브러리 업데이트 확인 중..."
        viewModelScope.launch {
            val result = repository.updateLibrary(getApplication())
            if (result.isSuccess) {
                _toastMessage.value = "라이브러리 업데이트 완료"
            } else {
                _toastMessage.value = "라이브러리 업데이트 실패"
            }
            _isUpdating.value = false
        }
    }
    
    fun resetState() {
        _uiState.value = UiState.Idle
    }

    private val _statusMessage = MutableLiveData<String>()
    val statusMessage: LiveData<String> = _statusMessage

    private val _isDownloading = MutableLiveData<Boolean>(false)
    val isDownloading: LiveData<Boolean> = _isDownloading

    fun updateDownloadStatus(message: String, isDownloading: Boolean) {
        _statusMessage.value = message
        _isDownloading.value = isDownloading
    }

    private val _updateInfo = MutableLiveData<AppUpdateInfo?>()
    val updateInfo: LiveData<AppUpdateInfo?> = _updateInfo

    private val _versionStatus = MutableLiveData<String>()
    val versionStatus: LiveData<String> = _versionStatus

    fun checkAppUpdate(currentVersion: String) {
        viewModelScope.launch {
            val updater = AppUpdater(getApplication())
            when (val result = updater.checkForUpdate(currentVersion)) {
                is AppUpdater.UpdateResult.Available -> {
                    _updateInfo.value = result.updateInfo
                    _versionStatus.value = "현재 버전: $currentVersion / 최신 버전: ${result.updateInfo.version} (업데이트 가능)"
                }
                is AppUpdater.UpdateResult.NoUpdate -> {
                    _versionStatus.value = "현재 버전: $currentVersion (최신 버전입니다)"
                }
                is AppUpdater.UpdateResult.Error -> {
                    _versionStatus.value = "현재 버전: $currentVersion (업데이트 확인 실패)"
                }
            }
        }
    }
}
