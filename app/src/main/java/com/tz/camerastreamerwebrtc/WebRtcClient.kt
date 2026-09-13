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
import org.webrtc.RtpParameters
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

/** Какая камера используется для захвата. */
enum class CameraFacing { FRONT, BACK }

@SuppressLint("MissingPermission")
class WebRtcClient(
    context: Context,
    private val signalingUrl: String,
    private val eglBase: EglBase,
    private val cameraFacing: CameraFacing,
    private val onStatus: (String) -> Unit
) {
    private companion object {
        const val tag = "WebRtcClient"

        // FIX: 640x480 вместо 1280x720.
        // Snapdragon 800 (msm8974) не тянет 720p30 с WebRTC overhead'ом —
        // энкодер дропает кадры, fps падает до 12-16, ICE рвётся.
        // 480p@24 работает стабильно.
        const val VIDEO_WIDTH_MAX = 640
        const val VIDEO_HEIGHT_MAX = 480
        const val VIDEO_FPS = 24

        // FIX: битрейт пропорционально ниже — для 640x480 достаточно 1.2 Mbps.
        // Меньше битрейт → меньше нагрузка на Wi-Fi → меньше потерь.
        const val TARGET_MAX_BITRATE_BPS = 1_200_000
        const val TARGET_MIN_BITRATE_BPS = 500_000
    }

    private val appContext: Context = context.applicationContext

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        // FIX: увеличенные таймауты — при -70 dBm пакеты ходят медленно.
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
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

    private val stopped = AtomicBoolean(false)

    // ----------------- Public API -----------------

    fun attachLocalRenderer(r: SurfaceViewRenderer) {
        localRenderer = r
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
        if (!stopped.compareAndSet(false, true)) return

        try { ws?.close(1000, "bye") } catch (e: Exception) { Log.w(tag, "WS: ${e.message}") }
        ws = null

        // 1. Останавливаем камеру.
        try { videoCapturer?.stopCapture() } catch (e: Exception) { Log.w(tag, "capture: ${e.message}") }
        try { videoCapturer?.dispose() } catch (_: Exception) {}
        videoCapturer = null

        // 2. Отписываем рендерер.
        try { localRenderer?.let { r -> localVideoTrack?.removeSink(r) } } catch (_: Exception) {}

        // 3. Закрываем PC (остановит RTP-потоки и encoder).
        try { peerConnection?.close() } catch (_: Exception) {}
        peerConnection = null

        // 4. Dispose источников.
        try { videoSource?.dispose() } catch (_: Exception) {}
        videoSource = null
        try { audioSource?.dispose() } catch (_: Exception) {}
        audioSource = null

        // 5. Треки.
        try { localVideoTrack?.dispose() } catch (_: Exception) {}
        localVideoTrack = null
        try { localAudioTrack?.dispose() } catch (_: Exception) {}
        localAudioTrack = null

        // 6. factory до surfaceHelper.
        try { factory?.dispose() } catch (_: Exception) {}
        factory = null

        // 7. SurfaceTextureHelper — последним.
        try { surfaceHelper?.dispose() } catch (_: Exception) {}
        surfaceHelper = null

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
                Log.i(tag, "ICE state: $state")

                // Как только ICE поднялся — настраиваем sender.
                // По умолчанию Android стартует с 300 kbps.
                if (state == PeerConnection.IceConnectionState.CONNECTED) {
                    configureVideoSender()
                }

                if (state == PeerConnection.IceConnectionState.FAILED) {
                    Log.w(tag, "ICE failed — stopping")
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

        val preferred = when (cameraFacing) {
            CameraFacing.FRONT -> enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            CameraFacing.BACK -> enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
        }
        val deviceName = preferred
            ?: enumerator.deviceNames.firstOrNull()
            ?: throw IllegalStateException("No camera found")

        Log.i(tag, "Selected camera: $deviceName (facing=$cameraFacing)")

        val capturer = enumerator.createCapturer(deviceName, null)
            ?: throw IllegalStateException("Cannot create capturer")

        videoCapturer = capturer

        val (w, h) = pickBestFormat(enumerator, deviceName)
        Log.i(tag, "Selected capture format: ${w}x${h}")

        videoSource = factory?.createVideoSource(false)
            ?: throw IllegalStateException("Cannot create video source")

        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)

        capturer.initialize(surfaceHelper, appContext, videoSource!!.capturerObserver)
        capturer.startCapture(w, h, VIDEO_FPS)

        localVideoTrack = factory?.createVideoTrack("video0", videoSource)

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        audioSource = factory?.createAudioSource(audioConstraints)
        localAudioTrack = factory?.createAudioTrack("audio0", audioSource)

        peerConnection?.addTrack(localVideoTrack, listOf("stream0"))
        peerConnection?.addTrack(localAudioTrack, listOf("stream0"))

        localRenderer?.let { localVideoTrack?.addSink(it) }
    }

    private fun pickBestFormat(enumerator: Camera2Enumerator, device: String): Pair<Int, Int> {
        val formats = enumerator.getSupportedFormats(device)
        if (formats.isNullOrEmpty()) return 640 to 480

        val target = formats
            .filter { it.width <= VIDEO_WIDTH_MAX && it.height <= VIDEO_HEIGHT_MAX }
            .maxByOrNull { it.width * it.height }

        return target?.let { it.width to it.height } ?: (640 to 480)
    }

    // ----------------- Video Sender Configuration -----------------

    /**
     * Задаёт явные параметры видео-сендера. Без этого Android использует
     * дефолтный bitrate 300 kbps и уходит в 180×320.
     *
     * BALANCED: компромисс между разрешением и fps — важно для старого
     * железа (msm8974), которое не тянет 720p30.
     */
    private fun configureVideoSender() {
        val pc = peerConnection ?: return
        val sender = pc.senders.firstOrNull { it.track()?.kind() == "video" }
        if (sender == null) {
            Log.w(tag, "Video sender not found, skip config")
            return
        }

        try {
            val params = sender.parameters

            // FIX: BALANCED вместо MAINTAIN_RESOLUTION.
            // При нехватке полосы позволяем WebRTC снижать и разрешение, и fps.
            params.degradationPreference =
                RtpParameters.DegradationPreference.BALANCED

            params.encodings?.forEach { enc ->
                enc.maxBitrateBps = TARGET_MAX_BITRATE_BPS
                enc.minBitrateBps = TARGET_MIN_BITRATE_BPS
                enc.maxFramerate = VIDEO_FPS
            }

            val ok = sender.setParameters(params)
            Log.i(
                tag,
                "Video sender configured: ok=$ok " +
                        "(max=${TARGET_MAX_BITRATE_BPS}, min=${TARGET_MIN_BITRATE_BPS}, " +
                        "fps=$VIDEO_FPS, balanced)"
            )

            val now = sender.parameters
            now.encodings?.forEachIndexed { i, e ->
                Log.i(
                    tag,
                    "  encoding[$i]: max=${e.maxBitrateBps}, " +
                            "min=${e.minBitrateBps}, fps=${e.maxFramerate}"
                )
            }
        } catch (ex: Exception) {
            Log.e(tag, "configureVideoSender failed", ex)
        }
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
                val desc = t.message?.takeIf { it.isNotBlank() }
                    ?: t::class.java.simpleName
                Log.w(tag, "Signaling failure: $desc (response=${response?.code})")
                onStatus("Signaling error: $desc")
                try { stop() } catch (_: Exception) {}
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(tag, "Signaling closing: $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(tag, "Signaling closed: $code $reason")
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