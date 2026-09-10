package app.opencall.gateway

import android.content.Context
import android.media.AudioManager
import android.util.Log
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * WebRTC audio bridge between this phone and the browser (or AI) user.
 *
 * Signaling rides the same durable `calls_events` table as the browser uses:
 *   read  → gateway_get_signals(callId, afterSeq)   [skips own zero-uuid posts]
 *   write → gateway_post_signal(callId, kind, payload)
 *
 * Roles are asymmetric per flow (glare-free by construction):
 *   - OUTBOUND gateway call: browser offers → phone ANSWERS (joinAndAnswer)
 *   - INBOUND  bridge:       phone OFFERS  → browser answers (joinAndOffer)
 *
 * Only audio: mic up / speaker down, all on SPEAKERPHONE so the cellular
 * call acoustically passes through the hardware AEC.
 */
class WebRtcBridge(
    private val ctx: Context,
    private val deviceId: String,
    private val deviceSecret: String,
    /** fires once when the peer connection reaches CONNECTED (v1.5.1, used by Bridge) */
    private val onConnected: (() -> Unit)? = null,
    /** fires when the peer connection irrevocably FAILED (not transient disconnects) */
    private val onGone: (() -> Unit)? = null,
) {
    companion object {
        private const val TAG = "OpenCall/WebRTC"
        private const val STUN = "stun:stun.l.google.com:19302"
    }

    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    var callId: String? = null
    private var sigSeq: Int = 0
    /** true while we're the OFFERER (inbound bridge); peer's 'answer' then completes our SRD. */
    @Volatile private var isOfferer: Boolean = false
    private var pollThread: Thread? = null
    @Volatile private var closed: Boolean = false

    private val egl: EglBase = EglBase.create()

    /** One-time factory init (idempotent). */
    private fun ensureFactory() {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(ctx)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
        val enc = DefaultVideoEncoderFactory(egl.eglBaseContext, true, true)
        val dec = DefaultVideoDecoderFactory(egl.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(enc)
            .setVideoDecoderFactory(dec)
            .createPeerConnectionFactory()
        Log.i(TAG, "PeerConnectionFactory ready")
    }

    /**
     * OUTBOUND flow: browser posts 'offer' first; we answer it.
     */
    @Synchronized
    fun joinAndAnswer(room: String, callId: String) {
        start(room, callId, offer = false)
    }

    /**
     * INBOUND flow: phone posts the offer right away; the browser
     * (startMedia('callee')) picks it up from calls_events and answers.
     */
    @Synchronized
    fun joinAndOffer(room: String, callId: String) {
        start(room, callId, offer = true)
    }

    private fun start(room: String, callId: String, offer: Boolean) {
        if (this.pc != null && this.callId == callId) return   // already bridging this call
        close()

        this.callId = callId
        this.sigSeq = DeviceStore.sigSeq(ctx, callId)
        this.closed = false
        this.isOfferer = offer

        ensureFactory()

        val rtcConfig = PeerConnection.RTCConfiguration(listOf(PeerConnection.IceServer.builder(STUN).createIceServer())).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }

        audioSource = factory!!.createAudioSource(audioConstraints)
        audioTrack = factory!!.createAudioTrack("oc-audio", audioSource).also {
            it.setEnabled(true)
        }

        pc = factory!!.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                // non-trickle: browser also waits for full gather; still forward immediately
                postSignal("ice", JSONObject()
                    .put("candidate", c.sdp)
                    .put("sdpMid", c.sdpMid ?: "0")
                    .put("sdpMLineIndex", c.sdpMLineIndex))
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.i(TAG, "connection state → $newState")
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED ->
                        // audio path live — the honest "call is active" signal
                        try { onConnected?.invoke() } catch (_: Exception) {}
                    PeerConnection.PeerConnectionState.FAILED ->
                        // ICE gave up for good — report failure upward
                        try { onGone?.invoke() } catch (_: Exception) {}
                    else -> {
                        // DISCONNECTED can be transient (ICE restart) — browser
                        // signals 'bye' if it gives up; do nothing here.
                    }
                }
            }
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                Log.i(TAG, "ice state → $newState")
            }
            // unused callbacks
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
                Log.i(TAG, "remote track: ${transceiver.mediaType}")
            }
        }) ?: throw IllegalStateException("createPeerConnection returned null")

        // send our audio; receive theirs
        pc!!.addTrack(audioTrack, listOf("oc-stream"))

        if (offer) {
            // INBOUND bridge: we're the offerer — build+post the offer immediately.
            // Browser answers via get_call_events → handleSignal('answer').
            pc!!.createOffer(object : SdpObserverLog("createOffer") {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    val conn = pc ?: return
                    val d = desc ?: return
                    conn.setLocalDescription(SdpObserverLog("setLocal"), d)
                    postSignal("offer", JSONObject()
                        .put("type", "offer")
                        .put("sdp", d.description))
                    Log.i(TAG, "offer posted (${d.description.length} B SDP)")
                }
                override fun onCreateFailure(p0: String?) { Log.e(TAG, "createOffer failed: $p0") }
            }, MediaConstraints())
        }

        // poll for signals in the background
        pollThread = Thread {
            try { signalLoop() } catch (e: Exception) {
                if (!closed) Log.e(TAG, "signal loop crashed", e)
            }
        }.also { it.name = "oc-signaling"; it.start() }

        Log.i(TAG, "bridge started room=$room callId=$callId mode=${if (offer) "OFFER" else "ANSWER"}")
    }

    /** Polls gateway_get_signals; reacts to offer / ice / bye. */
    private fun signalLoop() {
        var backoff = 1500L
        while (!closed) {
            try {
                val callId = callId ?: break
                val raw = Rpc.rpcRaw(
                    "gateway_get_signals",
                    JSONObject()
                        .put("p_device_id", deviceId)
                        .put("p_secret", deviceSecret)
                        .put("p_call_id", callId)
                        .put("p_after_seq", sigSeq),
                )
                // set-returning function → JSONArray of { seq, kind, payload }
                val rows = SignalPage.parse(raw)
                for (r in rows) {
                    sigSeq = maxOf(sigSeq, r.seq)
                    when (r.kind) {
                        "offer" -> onOffer(r.payload)
                        "answer" -> onAnswer(r.payload)
                        "ice" -> onIce(r.payload)
                        "bye" -> { Log.i(TAG, "bye from peer"); close() }
                    }
                }
                backoff = 1200L
                Thread.sleep(if (rows.isEmpty()) 1200L else 250L)
            } catch (e: Exception) {
                if (closed) break
                Log.w(TAG, "signal poll error: ${e.message}")
                try { Thread.sleep(backoff) } catch (_: InterruptedException) {}
                backoff = minOf(backoff * 2, 8000L)
            }
        }
    }

    private fun onOffer(payload: JSONObject) {
        val conn = pc ?: return
        try {
            val sdp = payload.getString("sdp")
            val type = payload.optString("type", "offer")
            conn.setRemoteDescription(SdpObserverLog("setRemote"),
                SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp))

            val answerConstraints = MediaConstraints()
            conn.createAnswer(object : SdpObserverLog("createAnswer") {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    val d = desc ?: return
                    conn.setLocalDescription(SdpObserverLog("setLocal"), d)
                    val out = JSONObject()
                        .put("type", "answer")
                        .put("sdp", d.description)
                    postSignal("answer", out)
                    Log.i(TAG, "answer posted (${d.description.length} bytes SDP)")
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(p0: String?) { Log.e(TAG, "createAnswer: $p0") }
                override fun onSetFailure(p0: String?) { Log.e(TAG, "setLocal: $p0") }
            }, answerConstraints)

            // speaker ON as soon as we start bridging (acoustic path to the call)
            AudioRoute.speakerOn(ctx)
        } catch (e: Exception) {
            Log.e(TAG, "onOffer failed", e)
        }
    }

    /**
     * INBOUND bridge: we offered; the browser's answer completes the handshake.
     * Also turns the speaker on — from here the acoustic path is live.
     */
    private fun onAnswer(payload: JSONObject) {
        val conn = pc ?: return
        // ignore stray answers when we're the answerer (outbound flow)
        if (!isOfferer) { Log.i(TAG, "answer ignored (we are answerer)"); return }
        try {
            val sdp = payload.getString("sdp")
            val type = payload.optString("type", "answer")
            if (conn.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                Log.w(TAG, "answer in state ${conn.signalingState().name}, skipping")
                return
            }
            conn.setRemoteDescription(SdpObserverLog("setRemote(answer)"),
                SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp))
            Log.i(TAG, "remote answer applied")
            AudioRoute.speakerOn(ctx)
        } catch (e: Exception) {
            Log.e(TAG, "onAnswer failed", e)
        }
    }

    private fun onIce(payload: JSONObject) {
        val conn = pc ?: return
        try {
            val cand = IceCandidate(
                payload.optString("sdpMid", "0"),
                payload.optInt("sdpMLineIndex", 0),
                payload.getString("candidate"),
            )
            conn.addIceCandidate(cand)
        } catch (e: Exception) {
            Log.w(TAG, "addIce failed: ${e.message}")
        }
    }

    fun postSignal(kind: String, payload: JSONObject) {
        val callId = callId ?: return
        try {
            Rpc.rpc(
                "gateway_post_signal",
                JSONObject()
                    .put("p_device_id", deviceId)
                    .put("p_secret", deviceSecret)
                    .put("p_call_id", callId)
                    .put("p_kind", kind)
                    .put("p_payload", payload),
            )
        } catch (e: Exception) {
            Log.w(TAG, "postSignal $kind failed: ${e.message}")
        }
    }

    /** Post 'bye' and stop everything. */
    @Synchronized
    fun close() {
        if (closed && pc == null) return
        closed = true
        try {
            if (callId != null && pc != null) postSignal("bye", JSONObject())
        } catch (_: Exception) {}
        try { pc?.close() } catch (_: Exception) {}
        pc = null
        try { audioTrack?.setEnabled(false) } catch (_: Exception) {}
        try { audioSource?.dispose() } catch (_: Exception) {}
        audioSource = null
        audioTrack = null
        callId?.let { DeviceStore.saveSigSeq(ctx, it, sigSeq) }
        callId = null
        pollThread = null
        AudioRoute.speakerOff(ctx)
        Log.i(TAG, "bridge closed")
    }

    /** open: anonymous subclasses below override onCreateSuccess/onSetSuccess. */
    private open class SdpObserverLog(val tag: String) : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) { Log.w(TAG, "$tag failure: $p0") }
        override fun onSetFailure(p0: String?) { Log.w(TAG, "$tag failure: $p0") }
    }

    /** helper: parse the RPC result which may be a JSONArray of rows (or single row object) */
    private object SignalPage {
        fun parse(raw: Any?): List<Row> {
            return when (raw) {
                null -> emptyList()
                is org.json.JSONArray -> (0 until raw.length()).map { i ->
                    val o = raw.getJSONObject(i)
                    Row(o.getInt("seq"), o.getString("kind"), o.getJSONObject("payload"))
                }
                is JSONObject -> {
                    if (raw.has("seq")) listOf(Raw.toRow(raw)) else emptyList()
                }
                else -> emptyList()
            }
        }
        data class Row(val seq: Int, val kind: String, val payload: JSONObject)
        private object Raw {
            fun toRow(o: JSONObject) = Row(o.getInt("seq"), o.getString("kind"), o.getJSONObject("payload"))
        }
    }
}
