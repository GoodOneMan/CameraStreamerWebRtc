package com.tz.camerastreamerwebrtc

import android.app.Application
import android.content.Intent
import android.os.Build
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

    private companion object {
        const val TAG = "StreamVM"
        /** Задержка перед попыткой переподключения (мс) */
        const val RECONNECT_DELAY_MS = 3000L
    }

    // ── Глобальная инициализация WebRTC ──
    // Вызывается ОДИН раз при создании ViewModel.
    // Раньше вызывалась в WebRtcClient.initWebRtc() при каждом start(),
    // что приводило к нативному крашу при переподключении.
    init {
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(getApplication())
                    .createInitializationOptions()
            )
            Log.i(TAG, "PeerConnectionFactory initialized")
        } catch (e: Exception) {
            // Может бросить, если уже инициализирован — это нормально
            Log.w(TAG, "PeerConnectionFactory init: ${e.message}")
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

    // ── Состояние для автопереподключения ──
    private var isUserStopped = false
    private var reconnectJob: Job? = null
    private var lastSignalingUrl = ""
    private var lastCameraFacing = CameraFacing.BACK

    // ── Рендерер ──

    fun attachLocalRenderer(r: SurfaceViewRenderer) {
        renderer = r
        if (!rendererInitialized) {
            try {
                r.init(eglBase.eglBaseContext, null)
                r.setMirror(true)
                rendererInitialized = true
            } catch (e: Exception) {
                Log.e(TAG, "renderer init failed", e)
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

    // ── Старт / стоп ──

    fun start(signalingUrl: String, facing: CameraFacing = CameraFacing.BACK) {
        lastSignalingUrl = signalingUrl
        lastCameraFacing = facing
        isUserStopped = false
        reconnectJob?.cancel()

        Log.i(TAG, "start(): $signalingUrl facing=$facing")
        if (_isStreaming.value) return

        // Запускаем foreground-сервис ДО создания WebRTC-клиента,
        // чтобы система не убила процесс во время установки соединения
        startStreamingService()

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
                            Log.i(TAG, "Status: $s")
                            _status.value = s
                        },
                        onDisconnect = { handleDisconnect() } // ← колбэк разрыва
                    )
                }
                client = c
                renderer?.let { c.attachLocalRenderer(it) }
                c.start()
                _isStreaming.value = true
            } catch (e: Exception) {
                Log.e(TAG, "Start failed", e)
                _status.value = "Error: ${e.message}"
                _isStreaming.value = false
                try { client?.stop() } catch (_: Exception) {}
                client = null
                stopStreamingService()
                handleDisconnect() // Попробуем переподключиться
            }
        }
    }

    fun stop() {
        isUserStopped = true          // ← флаг: пользователь сам остановил
        reconnectJob?.cancel()
        try { client?.stop() } catch (e: Exception) { Log.w(TAG, "stop: ${e.message}") }
        client = null
        _isStreaming.value = false
        _status.value = "Idle"
        stopStreamingService()
    }

    // ── Автопереподключение ──

    /**
     * Вызывается из WebRtcClient при обрыве WebSocket или падении ICE.
     * Если пользователь сам нажал Stop (isUserStopped == true) — ничего не делаем.
     */
    private fun handleDisconnect() {
        if (isUserStopped) return

        viewModelScope.launch {
            _status.value = "Connection lost. Reconnecting in ${RECONNECT_DELAY_MS / 1000}s..."
            reconnectJob?.cancel()
            reconnectJob = launch {
                delay(RECONNECT_DELAY_MS)
                if (!isUserStopped) {
                    Log.i(TAG, "Attempting reconnect...")
                    // Чистим старый клиент
                    try { client?.stop() } catch (_: Exception) {}
                    client = null
                    _isStreaming.value = false
                    // Рекурсивно вызываем start() с теми же параметрами
                    start(lastSignalingUrl, lastCameraFacing)
                } else {
                    stopStreamingService()
                }
            }
        }
    }

    // ── Foreground-сервис ──

    private fun startStreamingService() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, StreamingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }

    private fun stopStreamingService() {
        val ctx = getApplication<Application>()
        ctx.stopService(Intent(ctx, StreamingService::class.java))
    }

    // ── Очистка ──

    override fun onCleared() {
        stop()
        try { renderer?.let { if (rendererInitialized) it.release() } } catch (_: Exception) {}
        renderer = null
        rendererInitialized = false
        try { eglBase.release() } catch (_: Exception) {}
        super.onCleared()
    }
}