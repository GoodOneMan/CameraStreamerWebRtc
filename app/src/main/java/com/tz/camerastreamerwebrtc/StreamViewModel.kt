package com.tz.camerastreamerwebrtc

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceViewRenderer

class StreamViewModel(app: Application) : AndroidViewModel(app) {
    private companion object { const val tag = "StreamVM" }

    // Глобальная инициализация WebRTC (выполняется один раз при создании ViewModel)
    init {
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(getApplication())
                    .createInitializationOptions()
            )
        } catch (e: Exception) {
            Log.w(tag, "PeerConnectionFactory already initialized or init failed: ${e.message}")
        }
    }

    private var client: WebRtcClient? = null
    private val eglBase: EglBase = EglBase.create()
    private var renderer: SurfaceViewRenderer? = null
    private var rendererInitialized = false

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming

    // --- Параметры для автопереподключения ---
    private var isUserStopped = false
    private var reconnectJob: Job? = null
    private var lastSignalingUrl = ""
    private var lastCameraFacing = CameraFacing.BACK

    fun attachLocalRenderer(r: SurfaceViewRenderer) {
        renderer = r
        if (!rendererInitialized) {
            try {
                r.init(eglBase.eglBaseContext, null)
                r.setMirror(true)
                rendererInitialized = true
            } catch (e: Exception) {
                Log.e(tag, "renderer init failed", e)
            }
        }
        client?.attachLocalRenderer(r)
    }

    fun detachLocalRenderer(r: SurfaceViewRenderer) {
        try { client?.detachLocalRenderer(r) } catch (_: Exception) {}
        if (renderer === r) {
            renderer = null
            rendererInitialized = false
        }
    }

    fun reportPermissionError() {
        _status.value = "Error: camera/microphone permission denied"
    }

    fun start(signalingUrl: String, facing: CameraFacing = CameraFacing.BACK) {
        lastSignalingUrl = signalingUrl
        lastCameraFacing = facing
        isUserStopped = false
        reconnectJob?.cancel()

        Log.i(tag, "start() called: $signalingUrl facing=$facing")
        if (_isStreaming.value) return

        viewModelScope.launch {
            try {
                _status.value = "Connecting..."
                val c = withContext(Dispatchers.Default) {
                    WebRtcClient(
                        context = getApplication(),
                        signalingUrl = signalingUrl,
                        eglBase = eglBase,
                        cameraFacing = facing,
                        onStatus = { s ->
                            Log.i(tag, "Status: $s")
                            _status.value = s
                        },
                        onDisconnect = { handleDisconnect() } // Инжектим колбэк разрыва
                    )
                }
                client = c
                renderer?.let { c.attachLocalRenderer(it) }
                c.start()
                _isStreaming.value = true
            } catch (e: Exception) {
                Log.e(tag, "Start failed", e)
                _status.value = "Error: ${e.message}"
                _isStreaming.value = false
                try { client?.stop() } catch (_: Exception) {}
                client = null
                handleDisconnect() // Если старт упал, тоже пробуем переподключиться
            }
        }
    }

    private fun handleDisconnect() {
        if (isUserStopped) return // Если юзер сам нажал Stop, не reconnect'имся

        viewModelScope.launch {
            _status.value = "Connection lost. Reconnecting in 3s..."
            reconnectJob?.cancel()
            reconnectJob = launch {
                delay(3000)
                if (!isUserStopped) {
                    Log.i(tag, "Attempting to reconnect...")
                    try { client?.stop() } catch (_: Exception) {}
                    client = null
                    _isStreaming.value = false
                    start(lastSignalingUrl, lastCameraFacing)
                }
            }
        }
    }

    fun stop() {
        isUserStopped = true
        reconnectJob?.cancel()
        try { client?.stop() } catch (e: Exception) { Log.w(tag, "stop: ${e.message}") }
        client = null
        _isStreaming.value = false
        _status.value = "Idle"
    }

    override fun onCleared() {
        stop()
        try {
            renderer?.let { if (rendererInitialized) it.release() }
        } catch (_: Exception) {}
        renderer = null
        rendererInitialized = false
        try { eglBase.release() } catch (_: Exception) {}
        super.onCleared()
    }
}