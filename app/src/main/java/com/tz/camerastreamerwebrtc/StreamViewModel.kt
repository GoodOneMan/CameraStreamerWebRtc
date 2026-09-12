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
import org.webrtc.SurfaceViewRenderer

class StreamViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        const val tag = "StreamVM"
    }

    private var client: WebRtcClient? = null
    private var pendingRenderer: SurfaceViewRenderer? = null

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming

    fun attachLocalRenderer(renderer: SurfaceViewRenderer) {
        val c = client
        if (c != null) c.attachLocalRenderer(renderer)
        else pendingRenderer = renderer
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

                // EglBase.create() тяжело грузит CPU — уводим с UI-потока
                val c = withContext(Dispatchers.Default) {
                    WebRtcClient(
                        context = getApplication(),
                        signalingUrl = signalingUrl,
                        onStatus = { s ->
                            Log.i(tag, "Status: $s")
                            _status.value = s
                        }
                    )
                }
                client = c

                pendingRenderer?.let {
                    c.attachLocalRenderer(it)
                    pendingRenderer = null
                }

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
        pendingRenderer = null
        _isStreaming.value = false
        _status.value = "Idle"
    }

    override fun onCleared() {
        stop()
    }
}