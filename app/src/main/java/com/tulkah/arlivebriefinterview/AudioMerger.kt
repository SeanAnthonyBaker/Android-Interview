package com.tulkah.arlivebriefinterview

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

class AudioMerger {

    fun mergeAudioFiles(inputFiles: List<File>, outputFile: File): Boolean {
        if (inputFiles.isEmpty()) return false

        try {
            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var audioTrackIndex = -1
            var totalPtsOffset = 0L

            val bufferSize = 1048576 // 1MB
            val buffer = ByteBuffer.allocate(bufferSize)

            for (file in inputFiles) {
                val extractor = MediaExtractor()
                extractor.setDataSource(file.absolutePath)

                var trackIndex = -1
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME)
                    if (mime?.startsWith("audio/") == true) {
                        trackIndex = i
                        if (audioTrackIndex == -1) {
                            audioTrackIndex = muxer.addTrack(format)
                            muxer.start()
                        }
                        break
                    }
                }

                if (trackIndex == -1) {
                    extractor.release()
                    continue
                }

                extractor.selectTrack(trackIndex)
                val bufferInfo = MediaCodec.BufferInfo()
                var currentPtsOffset = 0L

                while (true) {
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break

                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.flags = extractor.sampleFlags
                    bufferInfo.presentationTimeUs = extractor.sampleTime + totalPtsOffset

                    muxer.writeSampleData(audioTrackIndex, buffer, bufferInfo)
                    currentPtsOffset = extractor.sampleTime
                    extractor.advance()
                }
                
                // Add a small gap between clips to avoid overlapping issues (e.g. 100ms)
                totalPtsOffset += currentPtsOffset + 100_000L 
                extractor.release()
            }

            if (audioTrackIndex != -1) {
                muxer.stop()
                muxer.release()
                return true
            }

            return false
        } catch (e: Exception) {
            Log.e("AudioMerger", "Merge failed", e)
            return false
        }
    }
}
