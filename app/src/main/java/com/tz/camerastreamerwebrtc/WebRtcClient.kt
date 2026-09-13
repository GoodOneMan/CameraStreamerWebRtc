package com.tz.camerastreamerwebrtc

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.Camera2Enumerator
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("MissingPermission")
class WebRtcClient(
    context: Context,
    private val signalingUrl: String,
    private val eglBase: EglBase,          // FIX: shared
    private val onStatus: (String) -> Unit
) {
    private companion object {
        const val tag = "WebRtcClient"
        const val VIDEO_WIDTH = 1280
        const val VIDEO_HEIGHT = 720
        const val VIDEO_FPS = 30
    }

    private val appContext: Context = context.applicationContext

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var surfaceHelper: SurfaceTextureHelper? = null

    private var ws: WebSocket? = null
    private var localRenderer: SurfaceViewRenderer? = null

    // FIX: атомарный флаг — защита от гонок stop() из WebSocket-потока и Main.
    private val stopped = AtomicBoolean(false)

    // ----------------- Public API -----------------

    fun attachLocalRenderer(r: SurfaceViewRenderer) {
        localRenderer = r
        // Рендерер уже инициализирован ViewModel'ью — здесь только подписываемся.
        try {
            localVideoTrack?.addSink(r)
        } catch (e: Exception) {
            Log.e(tag, "attachLocalRenderer: addSink failed", e)
        }
    }

    fun detachLocalRenderer(r: SurfaceViewRenderer) {
        try {
            localVideoTrack?.removeSink(r)
        } catch (_: Exception) {}
        if (localRenderer === r) localRenderer = null
    }

    fun start() {
        stopped.set(false)
        initWebRtc()
        initPeerConnection()
        startCapture()
        connectSignaling()
    }

    fun stop() {
        // FIX: атомарный guard.
        if (!stopped.compareAndSet(false, true)) return

        try { ws?.close(1000, "bye") } catch (e: Exception) { Log.w(tag, "WS: ${e.message}") }
        ws = null

        try { videoCapturer?.stopCapture() } catch (e: Exception) { Log.w(tag, "capture: ${e.message}") }
        try { videoCapturer?.dispose() } catch (_: Exception) {}
        videoCapturer = null

        // FIX: НЕ релизим рендерер — им владеет UI (ViewModel/AndroidView).
        try {
            localRenderer?.let { r -> localVideoTrack?.removeSink(r) }
        } catch (_: Exception) {}

        try { peerConnection?.close() } catch (_: Exception) {}
        peerConnection = null

        try { surfaceHelper?.dispose() } catch (_: Exception) {}
        surfaceHelper = null
        try { videoSource?.dispose() } catch (_: Exception) {}
        videoSource = null
        try { audioSource?.dispose() } catch (_: Exception) {}
        audioSource = null
        try { localVideoTrack?.dispose() } catch (_: Exception) {}
        localVideoTrack = null
        try { localAudioTrack?.dispose() } catch (_: Exception) {}
        localAudioTrack = null
        try { factory?.dispose() } catch (_: Exception) {}
        factory = null

        // FIX: EglBase и рендерер НЕ трогаем — они переиспользуются.

        onStatus("Stopped")
    }

    // ----------------- Init -----------------

    private fun initWebRtc() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .createInitializationOptions()
        )
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options().apply { disableEncryption = false })
            .createPeerConnectionFactory()
    }

    private fun initPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory?.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                val json = JSONObject().apply {
                    put("type", "candidate")
                    put("candidate", candidate.sdp)
                    put("sdpMid", candidate.sdpMid ?: "")
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                }
                ws?.send(json.toString())
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                onStatus("ICE: $state")
                if (state == PeerConnection.IceConnectionState.FAILED) {
                    Log.w(tag, "ICE failed — stopping")
                    // FIX: при провале ICE сами инициируем чистку.
                    // WebSocket close() запустит ветку onClosed → onStatus("Stopped").
                    try { ws?.close(1000, "ice-failed") } catch (_: Exception) {}
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        }) ?: throw IllegalStateException("PeerConnection creation failed")
    }

    private fun startCapture() {
        val enumerator = Camera2Enumerator(appContext)
        val deviceName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: throw IllegalStateException("No camera found")

        val capturer = enumerator.createCapturer(deviceName, null)
            ?: throw IllegalStateException("Cannot create capturer")

        videoCapturer = capturer
        videoSource = factory?.createVideoSource(false)
            ?: throw IllegalStateException("Cannot create video source")

        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)

        capturer.initialize(surfaceHelper, appContext, videoSource!!.capturerObserver)
        capturer.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)

        localVideoTrack = factory?.createVideoTrack("video0", videoSource)

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        audioSource = factory?.createAudioSource(audioConstraints)
        localAudioTrack = factory?.createAudioTrack("audio0", audioSource)

        peerConnection?.addTrack(localVideoTrack, listOf("stream0"))
        peerConnection?.addTrack(localAudioTrack, listOf("stream0"))

        // FIX: если рендерер уже привязан — сразу навешиваем.
        localRenderer?.let { localVideoTrack?.addSink(it) }
    }

    // ----------------- Signaling -----------------

    private fun connectSignaling() {
        val request = Request.Builder().url(signalingUrl).build()
        ws = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onStatus("Signaling connected")
                createOffer()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(tag, ">> $text")
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "answer" -> applyAnswer(json.getString("sdp"))
                        "candidate" -> addRemoteCandidate(json)
                        "bye" -> {
                            onStatus("Peer disconnected")
                            stop()
                        }
                    }
                } catch (ex: Exception) {
                    Log.e(tag, "onMessage failed", ex)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(tag, "Signaling failure: ${t.message}")
                // FIX: сообщаем наверх и завершаем сессию — иначе PC «зависнет» подключённым.
                onStatus("Signaling error: ${t.message}")
                try { stop() } catch (_: Exception) {}
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(tag, "Signaling closing: $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(tag, "Signaling closed: $code $reason")
                // FIX: реагируем и на нормальное закрытие — иначе клиент «висит».
                try { stop() } catch (_: Exception) {}
            }
        })
    }

    private fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        val json = JSONObject().apply {
                            put("type", "offer")
                            put("sdp", sdp.description)
                        }
                        ws?.send(json.toString())
                    }
                }, sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(tag, "createOffer: $error")
                onStatus("Offer error: $error")
            }
            override fun onSetFailure(error: String?) {
                Log.e(tag, "setLocal: $error")
                onStatus("SetLocal error: $error")
            }
        }, constraints)
    }

    private fun applyAnswer(sdp: String) {
        val sd = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                Log.i(tag, "setRemoteDescription(answer) OK")
            }
            override fun onSetFailure(error: String?) {
                Log.e(tag, "setRemoteDescription(answer) failed: $error")
                // FIX: раньше это молчало.
                onStatus("Answer rejected: $error")
            }
        }, sd)
    }

    private fun addRemoteCandidate(json: JSONObject) {
        val cand = json.optString("candidate")
        if (cand.isNullOrEmpty()) return
        val c = IceCandidate(
            json.optString("sdpMid", "0"),
            json.optInt("sdpMLineIndex", 0),
            cand
        )
        peerConnection?.addIceCandidate(c)
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) { Log.e(tag, "create: $error") }
        override fun onSetFailure(error: String?) { Log.e(tag, "set: $error") }
    }
}