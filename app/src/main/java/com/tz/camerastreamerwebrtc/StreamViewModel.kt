package com.tz.camerastreamerwebrtc

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer

class StreamViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        const val tag = "StreamVM"
    }

    private var client: WebRtcClient? = null

    // FIX: EglBase живёт здесь, чтобы переиспользоваться между сессиями.
    private val eglBase: EglBase = EglBase.create()

    // FIX: единый рендерер на всё время жизни ViewModel.
    // Не обнуляем его в stop() — иначе при рестарте превью не появится.
    private var renderer: SurfaceViewRenderer? = null
    private var rendererInitialized = false

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming

    fun attachLocalRenderer(r: SurfaceViewRenderer) {
        renderer = r

        // FIX: инициализируем ровно один раз на жизнь рендерера.
        if (!rendererInitialized) {
            try {
                r.init(eglBase.eglBaseContext, null)
                r.setMirror(true)
                rendererInitialized = true
            } catch (e: Exception) {
                Log.e(tag, "renderer init failed", e)
            }
        }

        // Если клиент уже есть — сразу подписываемся.
        client?.attachLocalRenderer(r)
    }

    fun detachLocalRenderer(r: SurfaceViewRenderer) {
        // Вызывается из AndroidView.onRelease при уничтожении композиции.
        try {
            client?.detachLocalRenderer(r)
        } catch (_: Exception) {}
        if (renderer === r) {
            renderer = null
            rendererInitialized = false
        }
    }

    fun reportPermissionError() {
        _status.value = "Error: camera/microphone permission denied"
    }

    fun start(signalingUrl: String) {
        Log.i(tag, "start() called: $signalingUrl")
        if (_isStreaming.value) return
        viewModelScope.launch {
            try {
                _status.value = "Connecting..."

                // FIX: всю тяжёлую инициализацию — на background.
                val c = withContext(Dispatchers.Default) {
                    WebRtcClient(
                        context = getApplication(),
                        signalingUrl = signalingUrl,
                        eglBase = eglBase,      // FIX: shared EglBase
                        onStatus = { s ->
                            Log.i(tag, "Status: $s")
                            _status.value = s
                        }
                    )
                }
                client = c

                // Attach рендерера — на Main.
                renderer?.let { c.attachLocalRenderer(it) }

                c.start()
                _isStreaming.value = true
            } catch (e: Exception) {
                Log.e(tag, "Start failed", e)
                _status.value = "Error: ${e.message}"
                _isStreaming.value = false
                try { client?.stop() } catch (_: Exception) {}
                client = null
            }
        }
    }

    fun stop() {
        try { client?.stop() } catch (e: Exception) { Log.w(tag, "stop: ${e.message}") }
        client = null
        // FIX: renderer НЕ сбрасываем — он живёт в UI и переиспользуется при рестарте.
        _isStreaming.value = false
        _status.value = "Idle"
    }

    override fun onCleared() {
        stop()

        // Освобождаем рендерер и EglBase только когда ViewModel умирает.
        try {
            renderer?.let { if (rendererInitialized) it.release() }
        } catch (_: Exception) {}
        renderer = null
        rendererInitialized = false

        try { eglBase.release() } catch (_: Exception) {}

        super.onCleared()
    }
}