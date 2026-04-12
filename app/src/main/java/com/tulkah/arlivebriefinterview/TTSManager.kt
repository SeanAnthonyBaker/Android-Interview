package com.tulkah.arlivebriefinterview

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class TTSManager(context: Context) {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    
    var onSpeakDone: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                isInitialized = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    
                    override fun onDone(utteranceId: String?) {
                        onSpeakDone?.invoke()
                    }
                    
                    override fun onError(utteranceId: String?) {
                        Log.e("TTSManager", "Error in TTS playback")
                        onSpeakDone?.invoke() // Proceed anyway
                    }
                })
            }
        }
    }

    fun speak(text: String) {
        if (!isInitialized) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "UtteranceId")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}
