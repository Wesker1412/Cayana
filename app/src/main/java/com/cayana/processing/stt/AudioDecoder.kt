package com.cayana.processing.stt

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.cayana.core.logging.CayanaLogger
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Robust audio decoding utility that decodes various audio formats (WAV, M4A, MP3, AAC)
 * into standard 16 kHz mono float PCM samples in the range [-1.0f, 1.0f].
 *
 * Provides:
 * 1. Direct WAV PCM parsing (instant and dependency-free on Android & JVM).
 * 2. MediaExtractor + MediaCodec fallback for compressed audio formats.
 * 3. Sample-accurate time slicing [startMs, startMs + durationMs].
 * 4. Silence detection via RMS energy thresholding.
 */
object AudioDecoder {

    const val TARGET_SAMPLE_RATE = 16000
    const val SILENCE_RMS_THRESHOLD = 0.003f

    /**
     * Decodes an audio file (from Uri) into 16 kHz mono float PCM samples in range [-1.0f, 1.0f].
     */
    fun decodeToMono16k(context: Context, uri: Uri): FloatArray {
        // Try direct WAV parser first
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val wavSamples = decodeWavStream(stream)
                if (wavSamples != null) {
                    return wavSamples
                }
            }
        } catch (_: Exception) {}

        // Fall back to MediaExtractor + MediaCodec for compressed formats
        return decodeViaMediaCodec(context, uri)
    }

    /**
     * Extracts a sample-accurate slice corresponding to [startMs, startMs + durationMs].
     */
    fun sliceSamples(
        samples: FloatArray,
        startMs: Long,
        durationMs: Long,
        sampleRate: Int = TARGET_SAMPLE_RATE
    ): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)
        val startSample = ((startMs * sampleRate) / 1000L).toInt().coerceIn(0, samples.size)
        val endSample = (((startMs + durationMs) * sampleRate) / 1000L).toInt().coerceIn(startSample, samples.size)
        if (startSample >= endSample) return FloatArray(0)
        return samples.copyOfRange(startSample, endSample)
    }

    /**
     * Computes the Root Mean Square (RMS) amplitude of audio samples.
     */
    fun computeRms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0.0f
        var sumSquares = 0.0
        for (sample in samples) {
            sumSquares += (sample * sample)
        }
        return sqrt((sumSquares / samples.size)).toFloat()
    }

    /**
     * Checks if audio chunk is pure silence below detection threshold.
     */
    fun isSilence(samples: FloatArray, threshold: Float = SILENCE_RMS_THRESHOLD): Boolean {
        return computeRms(samples) < threshold
    }

    /**
     * Reads an uncompressed WAV PCM stream directly.
     */
    fun decodeWavStream(inputStream: InputStream): FloatArray? {
        val bytes = inputStream.readBytes()
        if (bytes.size < 44) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        val riff = ByteArray(4)
        buffer.get(riff)
        if (String(riff) != "RIFF") return null
        buffer.int // skip chunkSize
        val wave = ByteArray(4)
        buffer.get(wave)
        if (String(wave) != "WAVE") return null

        var audioFormat = 1
        var numChannels = 1
        var sampleRate = 16000
        var bitsPerSample = 16
        var dataOffset = -1
        var dataSize = 0

        while (buffer.remaining() >= 8) {
            val chunkIdBytes = ByteArray(4)
            buffer.get(chunkIdBytes)
            val chunkId = String(chunkIdBytes)
            val chunkSize = buffer.int

            if (chunkId == "fmt ") {
                audioFormat = buffer.short.toInt()
                numChannels = buffer.short.toInt()
                sampleRate = buffer.int
                buffer.int // byteRate
                buffer.short // blockAlign
                bitsPerSample = buffer.short.toInt()
                // skip any extra format bytes
                val remainingFormatBytes = chunkSize - 16
                if (remainingFormatBytes > 0 && buffer.remaining() >= remainingFormatBytes) {
                    buffer.position(buffer.position() + remainingFormatBytes)
                }
            } else if (chunkId == "data") {
                dataOffset = buffer.position()
                dataSize = minOf(chunkSize, buffer.remaining())
                break
            } else {
                if (chunkSize > 0 && buffer.remaining() >= chunkSize) {
                    buffer.position(buffer.position() + chunkSize)
                } else {
                    break
                }
            }
        }

        if (dataOffset < 0 || audioFormat != 1) return null // Only PCM format 1 is directly decoded

        // Extract raw samples
        buffer.position(dataOffset)
        val numSamplesPerChannel: Int
        val monoFloats: FloatArray

        if (bitsPerSample == 16) {
            val totalSamples = dataSize / 2
            numSamplesPerChannel = totalSamples / maxOf(1, numChannels)
            monoFloats = FloatArray(numSamplesPerChannel)
            for (i in 0 until numSamplesPerChannel) {
                var channelSum = 0f
                for (ch in 0 until numChannels) {
                    if (buffer.remaining() >= 2) {
                        channelSum += (buffer.short.toFloat() / 32768.0f)
                    }
                }
                monoFloats[i] = (channelSum / maxOf(1, numChannels)).coerceIn(-1.0f, 1.0f)
            }
        } else if (bitsPerSample == 8) {
            val totalSamples = dataSize
            numSamplesPerChannel = totalSamples / maxOf(1, numChannels)
            monoFloats = FloatArray(numSamplesPerChannel)
            for (i in 0 until numSamplesPerChannel) {
                var channelSum = 0f
                for (ch in 0 until numChannels) {
                    if (buffer.remaining() >= 1) {
                        val unsigned = (buffer.get().toInt() and 0xFF)
                        channelSum += ((unsigned - 128) / 128.0f)
                    }
                }
                monoFloats[i] = (channelSum / maxOf(1, numChannels)).coerceIn(-1.0f, 1.0f)
            }
        } else {
            return null
        }

        // Resample to 16 kHz if necessary
        return if (sampleRate != TARGET_SAMPLE_RATE) {
            resample(monoFloats, sampleRate, TARGET_SAMPLE_RATE)
        } else {
            monoFloats
        }
    }

    /**
     * Resamples mono audio using linear interpolation.
     */
    fun resample(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (input.isEmpty() || srcRate == dstRate) return input
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val outLength = (input.size / ratio).toInt()
        val output = FloatArray(outLength)

        for (i in 0 until outLength) {
            val srcIndex = i * ratio
            val index0 = srcIndex.toInt()
            val frac = (srcIndex - index0).toFloat()
            val sample0 = input[index0.coerceIn(0, input.size - 1)]
            val sample1 = input[(index0 + 1).coerceIn(0, input.size - 1)]
            output[i] = sample0 + frac * (sample1 - sample0)
        }
        return output
    }

    /**
     * Decodes compressed audio via MediaExtractor & MediaCodec.
     */
    private fun decodeViaMediaCodec(context: Context, uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                val format = extractor.getTrackFormat(it)
                format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return FloatArray(0)

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return FloatArray(0)
            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else TARGET_SAMPLE_RATE
            val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 1

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmData = ArrayList<Short>()
            val bufferInfo = MediaCodec.BufferInfo()
            var isEos = false

            while (!isEos) {
                val inputIndex = codec.dequeueInputBuffer(5000L)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 5000L)
                if (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                        val shortBuffer = outputBuffer.asShortBuffer()
                        while (shortBuffer.hasRemaining()) {
                            pcmData.add(shortBuffer.get())
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isEos = true
                    }
                }
            }

            // Convert to mono float
            val numFrames = pcmData.size / maxOf(1, channelCount)
            val monoFloats = FloatArray(numFrames)
            for (i in 0 until numFrames) {
                var sum = 0f
                for (ch in 0 until channelCount) {
                    val pcmIndex = i * channelCount + ch
                    if (pcmIndex < pcmData.size) {
                        sum += (pcmData[pcmIndex].toFloat() / 32768.0f)
                    }
                }
                monoFloats[i] = (sum / maxOf(1, channelCount)).coerceIn(-1.0f, 1.0f)
            }

            return if (sampleRate != TARGET_SAMPLE_RATE) {
                resample(monoFloats, sampleRate, TARGET_SAMPLE_RATE)
            } else {
                monoFloats
            }
        } catch (e: Exception) {
            CayanaLogger.w("AudioDecoder", "Failed to decode via MediaCodec: ${e.message}")
            return FloatArray(0)
        } finally {
            try { codec?.stop(); codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }
}
