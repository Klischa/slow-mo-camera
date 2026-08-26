package com.klischa.slowmocamera.ai.interpolation

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Ассемблер видеопотока (Video Assembler).
 * Кодирует последовательность интерполированных/стабилизированных Bitmap в MP4/H.264
 * со строгим монотонным presentation timestamp (PTS), гарантируя точную длительность видео.
 */
class VideoAssembler(private val context: Context) {

    private val tag = "VideoAssembler"

    suspend fun assembleFramesToMp4(
        frames: List<Bitmap>,
        targetFps: Int,
        outputFileName: String
    ): Uri? = withContext(Dispatchers.IO) {
        if (frames.isEmpty()) return@withContext null

        val safeFps = targetFps.coerceIn(15, 240)
        val firstFrame = frames.first()
        val width = firstFrame.width
        val height = firstFrame.height

        val tempFile = File(context.cacheDir, outputFileName)
        if (tempFile.exists()) tempFile.delete()

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: Surface? = null

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 20_000_000) // 20 Mbps
                setInteger(MediaFormat.KEY_FRAME_RATE, safeFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            encoder.start()

            muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false

            val bufferInfo = MediaCodec.BufferInfo()
            val frameDurationUs = 1_000_000L / safeFps
            var writtenFramesCount = 0L

            for (frame in frames) {
                // 1. Отрисовываем кадр на входной Surface энкодера
                val canvas: Canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    inputSurface.lockHardwareCanvas()
                } else {
                    inputSurface.lockCanvas(null)
                }
                canvas.drawBitmap(frame, 0f, 0f, null)
                inputSurface.unlockCanvasAndPost(canvas)

                // 2. Считываем закодированные буферы со строгим таймстемпом
                var outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                while (outputIndex >= 0) {
                    val encodedBuffer = encoder.getOutputBuffer(outputIndex)
                    if (encodedBuffer != null) {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }

                        if (bufferInfo.size > 0) {
                            if (!muxerStarted) {
                                trackIndex = muxer.addTrack(encoder.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                            // Строгий монотонный PTS
                            bufferInfo.presentationTimeUs = writtenFramesCount * frameDurationUs
                            writtenFramesCount++
                            muxer.writeSampleData(trackIndex, encodedBuffer, bufferInfo)
                        }
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)
                    outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                }
            }

            // 3. Завершение кодирования (EOS)
            encoder.signalEndOfInputStream()

            // 4. Дренаж оставшихся буферов с корректным таймингом
            var outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 25_000)
            while (outputIndex >= 0) {
                val encodedBuffer = encoder.getOutputBuffer(outputIndex)
                if (encodedBuffer != null && bufferInfo.size > 0 && muxerStarted) {
                    bufferInfo.presentationTimeUs = writtenFramesCount * frameDurationUs
                    writtenFramesCount++
                    muxer.writeSampleData(trackIndex, encodedBuffer, bufferInfo)
                }
                encoder.releaseOutputBuffer(outputIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 25_000)
            }

            encoder.stop()
            encoder.release()
            encoder = null

            if (muxerStarted) {
                muxer.stop()
            }
            muxer.release()
            muxer = null

            val totalDurationSec = (writtenFramesCount * frameDurationUs) / 1_000_000f
            Log.i(tag, "Видео успешно собрано: $writtenFramesCount кадров, длительность: ${String.format(java.util.Locale.US, "%.2fs", totalDurationSec)}")

            return@withContext saveToMediaStore(tempFile, outputFileName)
        } catch (e: Exception) {
            Log.e(tag, "Ошибка сборки видео: ${e.message}", e)
            return@withContext null
        } finally {
            try {
                encoder?.release()
                muxer?.release()
                inputSurface?.release()
            } catch (ignored: Exception) {}
        }
    }

    private fun saveToMediaStore(file: File, fileName: String): Uri? {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SlowMoCamera_Stabilized")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { input ->
                input.copyTo(out)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        }

        file.delete()
        return uri
    }
}
