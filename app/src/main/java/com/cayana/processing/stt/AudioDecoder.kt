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
 * Robust audio decoding utility that decodes audio formats (WAV, M4A, MP3, AAC)
 * into standard 16 kHz mono float PCM samples in the range [-1.0f, 1.0f].
 *
 * Core capabilities:
 * 1. Range-based decoding [decodeRangeToMono16k]: decodes ONLY the requested time window
 *    [startMs, startMs + durationMs], bounding memory consumption to CHUNK_DURATION_MS
 *    regardless of total file duration (supports 30s to 3h recordings).
 * 2. Direct WAV header inspection and range skipping (zero whole-file materialization).
 * 3. MediaExtractor.seekTo + bounded MediaCodec decoding for compressed formats.
 * 4. Silence detection via RMS energy thresholding.
 */
object AudioDecoder {

    const val TARGET_SAMPLE_RATE = 16000
    const val SILENCE_RMS_THRESHOLD = 0.003f

    /**
     * Decodes ONLY the requested time range [startMs, startMs + durationMs]
     * into 16 kHz mono float PCM samples in range [-1.0f, 1.0f].
     */
    fun decodeRangeToMono16k(
        context: Context,
        uri: Uri,
        startMs: Long,
        durationMs: Long
    ): FloatArray {
        if (durationMs <= 0) return FloatArray(0)

        // 1. Try streaming range-based WAV reader (instant and heap-bounded)
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val wavSamples = decodeWavRange(stream, startMs, durationMs)
                if (wavSamples != null) {
                    return wavSamples
                }
            }
        } catch (e: Exception) {
            CayanaLogger.d("AudioDecoder", "WAV range decode skipped for $uri: ${e.message}")
        }

        // 2. Fall back to seek-based MediaExtractor + MediaCodec for compressed audio
        return decodeRangeViaMediaCodec(context, uri, startMs, durationMs)
    }

    /**
     * Full-file decode convenience method (used when whole file is needed).
     */
    fun decodeToMono16k(context: Context, uri: Uri): FloatArray {
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val wavSamples = decodeWavStream(stream)
                if (wavSamples != null) {
                    return wavSamples
                }
            }
        } catch (_: Exception) {}

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
     * Reads ONLY the requested range [startMs, startMs + durationMs] directly from an uncompressed WAV stream.
     * Skips bytes leading up to startMs without allocating the preceding audio into memory.
     */
    fun decodeWavRange(
        inputStream: InputStream,
        startMs: Long,
        durationMs: Long
    ): FloatArray? {
        val header = ByteArray(12)
        if (readFully(inputStream, header) < 12) return null
        val headerBuf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        val riff = ByteArray(4)
        headerBuf.get(riff)
        if (String(riff) != "RIFF") return null
        headerBuf.int // chunkSize
        val wave = ByteArray(4)
        headerBuf.get(wave)
        if (String(wave) != "WAVE") return null

        var audioFormat = 1
        var numChannels = 1
        var sampleRate = 16000
        var bitsPerSample = 16
        var dataSize = 0L

        val chunkHeader = ByteArray(8)
        while (readFully(inputStream, chunkHeader) == 8) {
            val chunkBuf = ByteBuffer.wrap(chunkHeader).order(ByteOrder.LITTLE_ENDIAN)
            val chunkId = ByteArray(4)
            chunkBuf.get(chunkId)
            val chunkName = String(chunkId)
            val chunkSize = chunkBuf.int.toLong() and 0xFFFFFFFFL

            if (chunkName == "fmt ") {
                val fmtData = ByteArray(chunkSize.toInt())
                if (readFully(inputStream, fmtData) < fmtData.size) return null
                val fmtBuf = ByteBuffer.wrap(fmtData).order(ByteOrder.LITTLE_ENDIAN)
                audioFormat = fmtBuf.short.toInt()
                numChannels = fmtBuf.short.toInt()
                sampleRate = fmtBuf.int
                fmtBuf.int // byteRate
                fmtBuf.short // blockAlign
                bitsPerSample = fmtBuf.short.toInt()
            } else if (chunkName == "data") {
                dataSize = chunkSize
                break
            } else {
                skipBytes(inputStream, chunkSize)
            }
        }

        if (dataSize <= 0 || audioFormat != 1) return null
        val bytesPerSample = bitsPerSample / 8
        val bytesPerFrame = numChannels * bytesPerSample
        if (bytesPerFrame <= 0) return null

        val totalFrames = dataSize / bytesPerFrame
        val startFrame = ((startMs.coerceAtLeast(0L) * sampleRate) / 1000L).coerceIn(0L, totalFrames)
        val endFrame = if (durationMs >= (totalFrames * 1000L) / sampleRate) {
            totalFrames
        } else {
            val targetMs = startMs + durationMs
            if (targetMs < 0) totalFrames else ((targetMs * sampleRate) / 1000L).coerceIn(startFrame, totalFrames)
        }
        val framesToRead = (endFrame - startFrame).toInt()
        if (framesToRead <= 0) return FloatArray(0)

        val skipByteCount = startFrame * bytesPerFrame
        skipBytes(inputStream, skipByteCount)

        val bytesToRead = framesToRead * bytesPerFrame
        val chunkBytes = ByteArray(bytesToRead)
        val actualRead = readFully(inputStream, chunkBytes)
        val actualFrames = actualRead / bytesPerFrame
        if (actualFrames <= 0) return FloatArray(0)

        val buf = ByteBuffer.wrap(chunkBytes, 0, actualFrames * bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN)
        val monoFloats = FloatArray(actualFrames)

        if (bitsPerSample == 16) {
            for (i in 0 until actualFrames) {
                var channelSum = 0f
                for (ch in 0 until numChannels) {
                    channelSum += (buf.short.toFloat() / 32768.0f)
                }
                monoFloats[i] = (channelSum / maxOf(1, numChannels)).coerceIn(-1.0f, 1.0f)
            }
        } else if (bitsPerSample == 8) {
            for (i in 0 until actualFrames) {
                var channelSum = 0f
                for (ch in 0 until numChannels) {
                    val unsigned = (buf.get().toInt() and 0xFF)
                    channelSum += ((unsigned - 128) / 128.0f)
                }
                monoFloats[i] = (channelSum / maxOf(1, numChannels)).coerceIn(-1.0f, 1.0f)
            }
        } else {
            return null
        }

        return if (sampleRate != TARGET_SAMPLE_RATE) {
            resample(monoFloats, sampleRate, TARGET_SAMPLE_RATE)
        } else {
            monoFloats
        }
    }

    /**
     * Reads full uncompressed WAV PCM stream.
     */
    fun decodeWavStream(inputStream: InputStream): FloatArray? {
        return decodeWavRange(inputStream, 0L, Long.MAX_VALUE / 2)
    }

    private fun readFully(inputStream: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val count = inputStream.read(buffer, total, buffer.size - total)
            if (count < 0) break
            total += count
        }
        return total
    }

    private fun skipBytes(inputStream: InputStream, count: Long) {
        var remaining = count
        val discard = ByteArray(minOf(8192, remaining.coerceAtLeast(0).toInt()))
        while (remaining > 0) {
            val skipped = inputStream.skip(remaining)
            if (skipped <= 0) {
                val read = inputStream.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
                if (read < 0) break
                remaining -= read
            } else {
                remaining -= skipped
            }
        }
    }

    /**
     * Decodes compressed audio range via MediaExtractor seeking & early codec stop.
     */
    private fun decodeRangeViaMediaCodec(
        context: Context,
        uri: Uri,
        startMs: Long,
        durationMs: Long
    ): FloatArray {
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

            val startUs = startMs * 1000L
            val endUs = (startMs + durationMs) * 1000L

            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmData = ArrayList<Short>()
            val bufferInfo = MediaCodec.BufferInfo()
            var isEos = false
            var feedingInput = true

            while (!isEos) {
                if (feedingInput) {
                    val inputIndex = codec.dequeueInputBuffer(5000L)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            val presentationTimeUs = extractor.sampleTime
                            if (sampleSize < 0 || presentationTimeUs > endUs + 1_000_000L) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                feedingInput = false
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 5000L)
                if (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    val pts = bufferInfo.presentationTimeUs
                    if (outputBuffer != null && bufferInfo.size > 0 && pts >= startUs) {
                        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                        val shortBuffer = outputBuffer.asShortBuffer()
                        while (shortBuffer.hasRemaining()) {
                            pcmData.add(shortBuffer.get())
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || pts > endUs) {
                        isEos = true
                    }
                }
            }

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
            CayanaLogger.w("AudioDecoder", "Failed to range-decode via MediaCodec: ${e.message}")
            return FloatArray(0)
        } finally {
            try { codec?.stop(); codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun decodeViaMediaCodec(context: Context, uri: Uri): FloatArray {
        return decodeRangeViaMediaCodec(context, uri, 0L, Long.MAX_VALUE / 2)
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
}
