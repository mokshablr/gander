package com.arjun.gander

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * A short video, made at test time by the device's own encoder.
 *
 * tests/fixtures has no video because its generator is Python, which has no encoder that
 * is not another dependency, while every Android device has one built in. Each frame is a
 * single flat colour, alternating, so the file is small and quick to make, and a player
 * that renders frames of it has decoded real pictures.
 */
object MadeVideo {

    const val WIDTH = 160
    const val HEIGHT = 96

    /**
     * Writes [frames] frames at [fps] to [file], encoded as [mime] in [container], one of
     * MediaMuxer's output formats: H.264 in an MP4, say, or VP8 in WebM.
     */
    fun write(file: File, mime: String, container: Int, frames: Int = 20, fps: Int = 10) {
        val format = MediaFormat.createVideoFormat(mime, WIDTH, HEIGHT).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            setInteger(MediaFormat.KEY_BIT_RATE, 250_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(mime)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(file.path, container)
        val info = MediaCodec.BufferInfo()
        var track = -1
        var queued = 0
        try {
            var ended = false
            while (!ended) {
                if (queued <= frames) {
                    val input = codec.dequeueInputBuffer(10_000)
                    if (input >= 0) {
                        val at = queued * 1_000_000L / fps
                        if (queued == frames) {
                            codec.queueInputBuffer(input, 0, 0, at, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            paint(codec.getInputImage(input)!!, queued)
                            codec.queueInputBuffer(input, 0, WIDTH * HEIGHT * 3 / 2, at, 0)
                        }
                        queued++
                    }
                }
                val output = codec.dequeueOutputBuffer(info, 10_000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                } else if (output >= 0) {
                    // The codec's setup arrives as a buffer of its own and is already in
                    // the format the track was added with
                    val setup = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!setup && info.size > 0) {
                        muxer.writeSampleData(track, codec.getOutputBuffer(output)!!, info)
                    }
                    codec.releaseOutputBuffer(output, false)
                    ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                }
            }
        } finally {
            codec.stop()
            codec.release()
            if (track >= 0) muxer.stop()
            muxer.release()
        }
    }

    /** Frame [n] as one colour, red and blue by turns, written plane by plane as Y, U and V. */
    private fun paint(image: Image, n: Int) {
        val yuv = if (n % 2 == 0) intArrayOf(81, 90, 240) else intArrayOf(41, 240, 110)
        image.planes.forEachIndexed { i, plane ->
            val width = if (i == 0) WIDTH else WIDTH / 2
            val height = if (i == 0) HEIGHT else HEIGHT / 2
            val buffer = plane.buffer
            for (row in 0 until height) {
                for (column in 0 until width) {
                    buffer.put(row * plane.rowStride + column * plane.pixelStride, yuv[i].toByte())
                }
            }
        }
    }
}
