package dev.gamecontrols.link.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import dev.gamecontrols.link.protocol.DecoderDevice
import dev.gamecontrols.link.protocol.DecoderOutput
import dev.gamecontrols.link.protocol.Packet
import dev.gamecontrols.link.protocol.ReleaseActions
import dev.gamecontrols.link.protocol.VideoPump
import java.nio.ByteBuffer
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class SurfaceDecoder(
    private val surface: Surface,
    private val presented: () -> Unit,
    private val failed: (String) -> Unit,
) {
    private val stopped = AtomicBoolean(false)
    private val worker = HandlerThread("GameLink-decoder").apply { start() }
    private val handler = Handler(worker.looper)
    private val pump = VideoPump(::create, presented)
    private val cleanupDone = CountDownLatch(1)
    private val cleanupFailure = AtomicReference<RuntimeException?>()

    init { handler.post(tick) }

    fun configure(config: Packet.Config) = pump.configure(config)
    fun offer(video: Packet.Video) = pump.offer(video, SystemClock.elapsedRealtime())

    private val tick: Runnable
        get() = object : Runnable {
            override fun run() {
                if (stopped.get()) return
                try {
                    pump.step(SystemClock.elapsedRealtime())
                    handler.postDelayed(this, 5)
                } catch (error: IOException) {
                    fail(error)
                } catch (error: IllegalStateException) {
                    fail(error)
                } catch (error: IllegalArgumentException) {
                    fail(error)
                } catch (error: SecurityException) {
                    fail(error)
                }
            }
        }

    private fun fail(error: Exception) {
        Log.e("GameLink", "Decoder stopped", error)
        failed("Decoder stopped: ${error.javaClass.simpleName}. Start a fresh session.")
    }

    fun close(): Boolean {
        if (stopped.compareAndSet(false, true)) {
            handler.post {
                try {
                    pump.close()
                } catch (error: RuntimeException) {
                    cleanupFailure.set(error)
                    Log.e("GameLink", "Decoder cleanup failed", error)
                    failed("Android reported a decoder cleanup failure.")
                } finally {
                    worker.quitSafely()
                    cleanupDone.countDown()
                }
            }
        }
        if (Thread.currentThread() === worker) return true
        return cleanupDone.await(1000, TimeUnit.MILLISECONDS) && cleanupFailure.get() == null
    }

    private fun create(config: Packet.Config): DecoderDevice {
        val codec = MediaCodec.createDecoderByType("video/avc")
        var started = false
        try {
            val geometry = config.geometry
            val format = MediaFormat.createVideoFormat("video/avc", geometry.encodedWidth, geometry.encodedHeight).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(config.csd0))
                if (config.csd1.isNotEmpty()) setByteBuffer("csd-1", ByteBuffer.wrap(config.csd1))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codec.configure(format, surface, null, 0)
            codec.start()
            started = true
            return object : DecoderDevice {
                private val info = MediaCodec.BufferInfo()
                override fun queue(frame: Packet.Video): Boolean {
                    val index = codec.dequeueInputBuffer(0)
                    if (index < 0) return false
                    val buffer = codec.getInputBuffer(index) ?: error("Decoder has no input buffer.")
                    check(buffer.capacity() >= frame.bytes.size) { "H.264 frame exceeds decoder input capacity." }
                    buffer.clear()
                    buffer.put(frame.bytes)
                    codec.queueInputBuffer(index, 0, frame.bytes.size, frame.ptsUs, 0)
                    return true
                }

                override fun dequeue(): DecoderOutput? {
                    val index = codec.dequeueOutputBuffer(info, 0)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val output = codec.outputFormat
                        val width = if (output.containsKey("crop-right") && output.containsKey("crop-left"))
                            output.getInteger("crop-right") - output.getInteger("crop-left") + 1
                        else output.getInteger(MediaFormat.KEY_WIDTH)
                        val height = if (output.containsKey("crop-bottom") && output.containsKey("crop-top"))
                            output.getInteger("crop-bottom") - output.getInteger("crop-top") + 1
                        else output.getInteger(MediaFormat.KEY_HEIGHT)
                        check(width == geometry.encodedWidth && height == geometry.encodedHeight) {
                            "Decoder crop differs from negotiated geometry."
                        }
                        return null
                    }
                    return if (index >= 0) DecoderOutput(index, info.presentationTimeUs) else null
                }

                override fun release(output: DecoderOutput, render: Boolean) {
                    codec.releaseOutputBuffer(output.token, render && !stopped.get() && surface.isValid)
                }

                override fun close() {
                    ReleaseActions.run({ codec.stop() }, { codec.release() })?.let { throw it }
                }
            }
        } catch (error: IllegalStateException) {
            ReleaseActions.run({ if (started) codec.stop() }, { codec.release() })?.let(error::addSuppressed)
            throw error
        } catch (error: IllegalArgumentException) {
            ReleaseActions.run({ if (started) codec.stop() }, { codec.release() })?.let(error::addSuppressed)
            throw error
        } catch (error: SecurityException) {
            ReleaseActions.run({ if (started) codec.stop() }, { codec.release() })?.let(error::addSuppressed)
            throw error
        }
    }
}
