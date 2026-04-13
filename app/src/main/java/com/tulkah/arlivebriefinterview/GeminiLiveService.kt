package com.tulkah.arlivebriefinterview

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*

/**
 * GeminiLiveService — Full duplex audio conversation with Gemini.
 * Gemini drives the interview: asks questions aloud, listens to answers,
 * and navigates through the brief's topics.
 */
class GeminiLiveService(private val context: Context) {

    companion object {
        private const val TAG = "GeminiLive"
        private const val CAPTURE_RATE = 16000
        private const val PLAYBACK_RATE = 24000 // Gemini outputs PCM at 24kHz
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    // --- Public state for UI ---
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _transcript = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    val transcript: StateFlow<List<TranscriptEntry>> = _transcript

    private val _activeTurnText = MutableStateFlow("")
    val activeTurnText: StateFlow<String> = _activeTurnText

    private val _isAiSpeaking = MutableStateFlow(false)
    val isAiSpeaking: StateFlow<Boolean> = _isAiSpeaking

    data class TranscriptEntry(val speaker: String, val text: String)

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR
    }

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var audioJob: Job? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()
    private val turnBuffer = StringBuilder()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    private val audioQueue = kotlinx.coroutines.channels.Channel<ByteArray>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private var playbackJob: Job? = null

    // Mic mute during AI speech to prevent echo feedback
    @Volatile private var micMuted = false

    /**
     * Connect to Gemini Live with audio response modality.
     * Gemini will SPEAK and LISTEN — driving the interview.
     */
    fun connect(apiKey: String, briefText: String) {
        if (_connectionState.value == ConnectionState.CONNECTED) return
        _connectionState.value = ConnectionState.CONNECTING
        addTranscript("system", "🔄 Connecting to Gemini Live...")

        val systemPrompt = buildInterviewerPrompt(briefText)

        // Force audio to speaker (not earpiece)
        enableSpeaker()
        initAudioPlayback()

        val client = OkHttpClient.Builder()
            .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()

        val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(wsUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                addTranscript("system", "✅ WebSocket open — sending setup...")

                val setup = JsonObject().apply {
                    val setupObj = JsonObject().apply {
                        addProperty("model", "models/gemini-3.1-flash-live-preview")
                        val genConfig = JsonObject().apply {
                            val modalities = com.google.gson.JsonArray()
                            modalities.add("AUDIO")
                            add("responseModalities", modalities)
                            // Voice config
                            val speechConfig = JsonObject().apply {
                                val voiceConfig = JsonObject().apply {
                                    val prebuilt = JsonObject().apply {
                                        addProperty("voiceName", "Puck")
                                    }
                                    add("prebuiltVoiceConfig", prebuilt)
                                }
                                add("voiceConfig", voiceConfig)
                            }
                            add("speechConfig", speechConfig)
                        }
                        add("generationConfig", genConfig)

                        // VAD config — be patient with speech detection
                        val vadConfig = JsonObject().apply {
                            val autoDetect = JsonObject().apply {
                                addProperty("disabled", false)
                                addProperty("startOfSpeechSensitivity", "START_SENSITIVITY_LOW")
                                addProperty("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                                addProperty("prefixPaddingMs", 1000)
                                addProperty("silenceDurationMs", 2000)
                            }
                            add("automaticActivityDetection", autoDetect)
                        }
                        add("realtimeInputConfig", vadConfig)

                        // System instructions — Gemini IS the interviewer
                        val sysInstr = JsonObject().apply {
                            val parts = com.google.gson.JsonArray()
                            val part = JsonObject().apply { addProperty("text", systemPrompt) }
                            parts.add(part)
                            add("parts", parts)
                        }
                        add("systemInstruction", sysInstr)
                    }
                    add("setup", setupObj)
                }

                val setupJson = gson.toJson(setup)
                Log.d(TAG, "Sending setup: ${setupJson.take(200)}...")
                webSocket.send(setupJson)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                handleServerMessage(webSocket, bytes.utf8())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(webSocket, text)
            }

            private fun handleServerMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JsonParser.parseString(text).asJsonObject
                    Log.d(TAG, "Received: ${text.take(150)}")

                    // --- ROOT LEVEL MESSAGE HANDLING ---
                    // According to Gemini Live protocol, transcription messages are root siblings, not children of serverContent.

                    // User transcription (what the user said)
                    if (json.has("inputTranscription")) {
                        val it = json.getAsJsonObject("inputTranscription")
                        if (it.has("text")) {
                            val userText = it.get("text").asString
                            if (userText.isNotBlank()) {
                                addTranscript("user", userText)
                                Log.d(TAG, "👤 User transcription: $userText")
                            }
                        }
                    }

                    // Output transcription (what the AI said)
                    if (json.has("outputTranscription")) {
                        val ot = json.getAsJsonObject("outputTranscription")
                        if (ot.has("text")) {
                            val transcriptionText = ot.get("text").asString
                            // Don't append if it's already in the buffer from serverContent to avoid duplication
                            // but verify it's added if turn complete is near
                            Log.d(TAG, "📝 Model transcription: $transcriptionText")
                        }
                    }

                    // Setup complete — start mic capture and trigger the interview
                    if (json.has("setupComplete")) {
                        Log.d(TAG, "✅ Setup complete — starting session")
                        _connectionState.value = ConnectionState.CONNECTED
                        addTranscript("system", "🎙️ Gemini Live READY — say hello to begin!")

                        // Start capturing audio immediately so user can prompt the model
                        startAudioCapture()
                        
                        return
                    }

                    // Handle server content
                    if (json.has("serverContent")) {
                        val sc = json.getAsJsonObject("serverContent")


                        // Interruption
                        if (sc.has("interrupted") && sc.get("interrupted").asBoolean) {
                            Log.d(TAG, "AI was interrupted")
                            turnBuffer.clear()
                            _activeTurnText.value = ""
                            _isAiSpeaking.value = false
                            micMuted = false
                            
                            // Clear queue and flush audio
                            audioQueue.tryReceive() // drain queue (simplistic clear)
                            var drained = true
                            while(drained) {
                                val res = audioQueue.tryReceive()
                                if (res.isFailure || res.isClosed) drained = false
                            }
                            
                            try {
                                audioTrack?.pause()
                                audioTrack?.flush()
                                audioTrack?.play()
                            } catch (e: Exception) {
                                Log.e(TAG, "AudioTrack flush failed", e)
                            }
                            
                            return
                        }

                        // Audio data from model
                        if (sc.has("modelTurn")) {
                            val mt = sc.getAsJsonObject("modelTurn")
                            if (mt.has("parts")) {
                                val parts = mt.getAsJsonArray("parts")
                                for (part in parts) {
                                    val p = part.asJsonObject
                                    if (p.has("inlineData")) {
                                        val inlineData = p.getAsJsonObject("inlineData")
                                        val mime = inlineData.get("mimeType").asString
                                        if (mime.startsWith("audio/pcm")) {
                                            val data = inlineData.get("data").asString
                                            Log.d(TAG, "🔊 Audio chunk: ${data.length} chars")
                                            playPcmAudio(data)
                                            _isAiSpeaking.value = true
                                            micMuted = true
                                        }
                                    }
                                    // Text parts (if any)
                                    if (p.has("text")) {
                                        turnBuffer.append(p.get("text").asString)
                                        _activeTurnText.value = turnBuffer.toString()
                                    }
                                }
                            }
                        }


                        // Turn complete
                        if (sc.has("turnComplete") && sc.get("turnComplete").asBoolean) {
                            val fullText = turnBuffer.toString().trim()
                            if (fullText.isNotBlank()) {
                                addTranscript("ai", fullText)
                            }
                            turnBuffer.clear()
                            _activeTurnText.value = ""
                            _isAiSpeaking.value = false
                            Log.d(TAG, "✅ Turn complete")
                            // Unmute mic after a brief grace period to avoid echo
                            scope.launch {
                                delay(800)
                                micMuted = false
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing message: ${e.message}", e)
                    addTranscript("system", "⚠️ Parse error: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}", t)
                _connectionState.value = ConnectionState.ERROR
                addTranscript("system", "⚠️ Error: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                _connectionState.value = ConnectionState.DISCONNECTED
                addTranscript("system", "🔌 Disconnected ($code)")
                stopAudioCapture()
                disableSpeaker()
            }
        })
    }

    // --- Speaker routing ---

    private fun enableSpeaker() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val speaker = devices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker != null) {
                audioManager.setCommunicationDevice(speaker)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        }

        // Boost voice call volume
        val maxVoice = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVoice, 0)
        Log.d(TAG, "Speaker enabled, modern communication device set")
    }

    private fun disableSpeaker() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    // --- Audio Playback (Gemini's voice output) ---

    private fun initAudioPlayback() {
        val bufSize = AudioTrack.getMinBufferSize(PLAYBACK_RATE, CHANNEL_OUT, ENCODING)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(ENCODING)
                    .setSampleRate(PLAYBACK_RATE)
                    .setChannelMask(CHANNEL_OUT)
                    .build()
            )
            .setBufferSizeInBytes(bufSize * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()
        
        playbackJob?.cancel()
        playbackJob = scope.launch(Dispatchers.IO) {
            for (bytes in audioQueue) {
                if (isActive) {
                    try {
                        val written = audioTrack?.write(bytes, 0, bytes.size) ?: -1
                        if (written < 0) {
                            Log.e(TAG, "AudioTrack write failed: $written")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Audio queue write error", e)
                    }
                }
            }
        }
        Log.d(TAG, "AudioTrack initialized: state=${audioTrack?.state}, playState=${audioTrack?.playState}")
    }

    private fun playPcmAudio(base64Data: String) {
        try {
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            audioQueue.trySend(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "Audio playback error", e)
        }
    }

    // --- Audio Capture (Microphone → Gemini) ---

    @Suppress("MissingPermission")
    private fun startAudioCapture() {
        val bufferSize = AudioRecord.getMinBufferSize(CAPTURE_RATE, CHANNEL_IN, ENCODING)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            CAPTURE_RATE,
            CHANNEL_IN,
            ENCODING,
            bufferSize * 2
        )
        audioRecord?.startRecording()
        Log.d(TAG, "AudioRecord started: state=${audioRecord?.state}")

        audioJob = scope.launch {
            val buffer = ShortArray(4096)
            while (isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0 && !micMuted) {
                    val byteBuffer = java.nio.ByteBuffer.allocate(read * 2)
                    byteBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until read) {
                        byteBuffer.putShort(buffer[i])
                    }
                    val base64Audio = Base64.encodeToString(byteBuffer.array(), Base64.NO_WRAP)

                    val audioMsg = JsonObject().apply {
                        val ri = JsonObject().apply {
                            val audioObj = JsonObject().apply {
                                addProperty("mimeType", "audio/pcm;rate=16000")
                                addProperty("data", base64Audio)
                            }
                            add("audio", audioObj)
                        }
                        add("realtimeInput", ri)
                    }
                    webSocket?.send(gson.toJson(audioMsg))
                }
            }
        }
    }

    private fun stopAudioCapture() {
        audioJob?.cancel()
        audioJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) { Log.e(TAG, "Error stopping capture", e) }
        audioRecord = null
    }

    fun disconnect() {
        stopAudioCapture()
        playbackJob?.cancel()
        playbackJob = null
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        webSocket?.close(1000, "Session ended")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        disableSpeaker()
    }

    private fun addTranscript(speaker: String, text: String) {
        val current = _transcript.value.toMutableList()
        current.add(TranscriptEntry(speaker, text))
        if (current.size > 500) {
            _transcript.value = current.takeLast(500)
        } else {
            _transcript.value = current
        }
    }

    /**
     * System prompt that makes Gemini the INTERVIEWER.
     * The brief is the interview plan Gemini follows.
     */
    private fun buildInterviewerPrompt(brief: String): String {
        return """
You are Phil Sage, an experienced Senior Managing Consultant and AI Director at Tulkah. You are conducting a live stakeholder interview.

PERSONA & VOICE:
- You have a warm, professional, but subtle New Zealand (Kiwi) accent. 
- IMPORTANT: DO NOT overdo the slang. Use Kiwi idioms naturally but sparingly (e.g., occasional "Kia ora", "cheers", or "mate"). 
- Your primary focus is being a clear, professional senior consultant; the accent is just a subtle flavor.

YOUR ROLE:
- You ARE the interviewer. You speak directly to the interviewee.
- Follow the interview brief below as your structured guide.
- Ask questions conversationally — do NOT read the brief verbatim.
- Use the brief's topics and structure as guardrails to keep the conversation focused and on-track.
- Adapt your questions based on what the interviewee says — follow interesting threads with probing follow-ups.

INTERVIEW TECHNIQUE:
- Start warm — build rapport before diving into specifics.
- Use open-ended questions: "Tell me about...", "How do you see...", "What's been your experience with..."
- When you hear something interesting, probe deeper using the 5 Whys technique: "Why is that?", "What's driving that?"
- Keep track of time — the brief has timing guidance for each section.
- Acknowledge good answers: "That's a really valuable insight...", "That's interesting because..."
- Transition between topics smoothly: "That's great context. Shifting gears slightly..."

CONVERSATION RULES:
- Speak naturally and professionally — you're a senior consultant, not a robot.
- ONE question at a time. Wait for the interviewee to finish before your next question.
- Keep your questions and responses concise (2-3 sentences max per turn).
- If the interviewee goes off-topic, gently steer back: "That's interesting. Coming back to..."
- When time is running short, summarise key takeaways and move to close.

THE INTERVIEW BRIEF (your plan):
$brief

Begin by warmly greeting the interviewee and setting the scene. Good luck!
        """.trimIndent()
    }
}
