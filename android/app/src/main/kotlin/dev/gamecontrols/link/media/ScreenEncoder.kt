package dev.gamecontrols.link.media

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Display
import android.view.Surface
import dev.gamecontrols.link.protocol.EncodeSize
import dev.gamecontrols.link.protocol.Geometry
import dev.gamecontrols.link.protocol.Packet
import dev.gamecontrols.link.protocol.ReleaseActions
import dev.gamecontrols.link.protocol.Wire
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ScreenEncoder(
    private val context: Context,
    private val resultCode: Int,
    private val consent: Intent,
    private val send: (Packet) -> Boolean,
    private val configured: (Geometry, Boolean) -> Unit,
    private val firstFrame: () -> Unit,
    private val failed: (String) -> Unit,
    private val closed: (String?) -> Unit,
) {
    private val worker = HandlerThread("GameLink-encoder").apply { start() }
    private val handler = Handler(worker.looper)
    private val cancelled = AtomicBoolean(false)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var codecStarted = false
    private var snapshot: DisplaySnapshot? = null
    private var configSent = false
    private var keySent = false
    private var geometry: Geometry? = null
    private val displays = context.getSystemService(DisplayManager::class.java)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() = fail("Android revoked screen sharing.")
        override fun onCapturedContentResize(width: Int, height: Int) {
            val expected = snapshot ?: return
            if (width != expected.width || height != expected.height) {
                fail("Capture resized or is not full-display. Restart with fresh full-screen consent.")
            }
        }
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            if (!isVisible) fail("Shared content is no longer visible.")
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) fail("Host display disappeared.")
        }
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY && !cancelled.get() &&
                DisplaySnapshot.read(context) != snapshot) {
                fail("Host display rotated or changed. Fresh pairing and screen consent are required.")
            }
        }
    }

    fun start() {
        handler.post {
            if (cancelled.get()) return@post
            try {
                startOwned()
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

    private fun startOwned() {
        val display = DisplaySnapshot.read(context)
        snapshot = display
        val choice = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && !it.isAlias && it.supportedTypes.any { type -> type.equals("video/avc", true) } }
            .sortedByDescending { it.isHardwareAccelerated }
            .firstNotNullOfOrNull { info ->
                val caps = info.getCapabilitiesForType("video/avc")
                if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats) return@firstNotNullOfOrNull null
                val video = caps.videoCapabilities ?: return@firstNotNullOfOrNull null
                val size = EncodeSize.fit(display.width, display.height, 1280, video.widthAlignment, video.heightAlignment)
                if (!video.areSizeAndRateSupported(size.first, size.second, 30.0)) return@firstNotNullOfOrNull null
                Triple(info, caps, size)
            } ?: error("No H.264 surface encoder supports this display at the initial 1280/30 profile.")
        val (info, caps, size) = choice
        val videoCaps = requireNotNull(caps.videoCapabilities) { "Encoder has no video capabilities." }
        val selected = Geometry(display.width, display.height, size.first, size.second, display.rotation)
        geometry = selected
        val format = MediaFormat.createVideoFormat("video/avc", size.first, size.second).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000.coerceIn(videoCaps.bitrateRange.lower,
                videoCaps.bitrateRange.upper))
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, 30f)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
            if (caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline }) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            }
        }
        val encoder = MediaCodec.createByCodecName(info.name)
        codec = encoder
        encoder.setCallback(codecCallback, handler)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = encoder.createInputSurface()
        encoder.start()
        codecStarted = true
        check(!cancelled.get()) { "Capture was stopped during encoder startup." }
        // CaptureService has already entered the visible mediaProjection foreground-service type.
        val capture = context.getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, consent)
            ?: error("Screen consent is no longer valid.")
        projection = capture
        capture.registerCallback(projectionCallback, handler)
        displays.registerDisplayListener(displayListener, handler)
        virtualDisplay = capture.createVirtualDisplay("Game Controls Link",
            size.first, size.second, display.density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            requireNotNull(surface), null, handler) ?: error("Android did not create a virtual display.")
        if (cancelled.get()) stop()
    }

    private val codecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit
        override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) = fail(error)

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (cancelled.get()) return
            try {
                check(!configSent) { "Encoder reconfigured; restart this session." }
                val current = requireNotNull(geometry)
                check(format.getInteger(MediaFormat.KEY_WIDTH) == current.encodedWidth &&
                    format.getInteger(MediaFormat.KEY_HEIGHT) == current.encodedHeight) { "Encoder changed dimensions." }
                val csd0 = copyCsd(format.getByteBuffer("csd-0") ?: error("Encoder did not supply SPS/PPS."))
                val csd1 = format.getByteBuffer("csd-1")?.let(::copyCsd) ?: ByteArray(0)
                check(send(Packet.Config(current, csd0, csd1))) { "Connection stopped before video configuration." }
                configSent = true
                val exactAspect = current.displayWidth.toLong() * current.encodedHeight ==
                    current.displayHeight.toLong() * current.encodedWidth
                configured(current, Build.VERSION.SDK_INT >= 32 || exactAspect)
            } catch (error: IOException) {
                fail(error)
            } catch (error: IllegalStateException) {
                fail(error)
            } catch (error: IllegalArgumentException) {
                fail(error)
            }
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (cancelled.get()) return
            try {
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    fail("Encoder ended its stream.")
                } else if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    check(configSent) { "Video arrived before H.264 configuration." }
                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    if (key || keySent) {
                        check(info.size <= Wire.MAX_VIDEO_BYTES) { "Encoded frame exceeds the transport limit." }
                        val buffer = codec.getOutputBuffer(index) ?: error("Encoder output is unavailable.")
                        check(info.offset >= 0 && info.offset.toLong() + info.size <= buffer.capacity()) {
                            "Encoder returned an invalid output buffer range."
                        }
                        val data = ByteArray(info.size)
                        buffer.duplicate().apply { clear(); limit(info.offset + info.size); position(info.offset); get(data) }
                        check(send(Packet.Video(info.presentationTimeUs, key, data))) { "Video transport is closed." }
                        if (!keySent) { keySent = true; firstFrame() }
                    }
                }
            } catch (error: IOException) {
                fail(error)
            } catch (error: IllegalStateException) {
                fail(error)
            } catch (error: IllegalArgumentException) {
                fail(error)
            } finally {
                try {
                    codec.releaseOutputBuffer(index, false)
                } catch (error: IllegalStateException) {
                    fail(error)
                }
            }
        }
    }

    private fun copyCsd(buffer: ByteBuffer): ByteArray {
        check(buffer.remaining() in 1..Wire.MAX_CSD_BYTES) { "Invalid H.264 codec configuration size." }
        return ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
    }

    private fun fail(error: Exception) {
        Log.e("GameLink", "Capture stopped", error)
        fail("Capture stopped: ${error.javaClass.simpleName}.")
    }

    private fun fail(message: String) {
        if (cancelled.get()) return
        failed(message)
        stop()
    }

    fun stop() {
        if (!cancelled.compareAndSet(false, true)) return
        handler.post {
            val failure = ReleaseActions.run(
                { displays.unregisterDisplayListener(displayListener) },
                { virtualDisplay?.release(); virtualDisplay = null },
                { if (codecStarted) codec?.stop(); codecStarted = false },
                { codec?.release(); codec = null },
                { surface?.release(); surface = null },
                { projection?.unregisterCallback(projectionCallback) },
                { projection?.stop(); projection = null },
            )
            if (failure != null) Log.e("GameLink", "Capture cleanup reported errors", failure)
            worker.quitSafely()
            closed(if (failure == null) null else "Android reported errors while releasing capture resources.")
        }
    }
}
