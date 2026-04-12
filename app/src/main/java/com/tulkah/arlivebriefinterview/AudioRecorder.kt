package com.tulkah.arlivebriefinterview

import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

class AudioRecorder(private val context: android.content.Context? = null) {
    private var recorder: MediaRecorder? = null

    fun startRecording(outputFile: File) {
        recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && context != null) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            recorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Recording failed", e)
        }
    }

    fun stopRecording() {
        try {
            recorder?.stop()
            recorder?.release()
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Stop recording failed", e)
        } finally {
            recorder = null
        }
    }
}
