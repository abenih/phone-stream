package dev.gamecontrols.link.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import dev.gamecontrols.link.LinkApp
import dev.gamecontrols.link.protocol.Geometry
import dev.gamecontrols.link.protocol.InputKind
import dev.gamecontrols.link.protocol.Packet
import dev.gamecontrols.link.protocol.Point

class ManualAccessibilityService : AccessibilityService() {
    private val app get() = application as LinkApp
    private val main = Handler(Looper.getMainLooper())
    private var stroke: GestureDescription.StrokeDescription? = null
    private var lastPoint: Point? = null
    private var revision = 0L
    private var completion: ((Boolean) -> Unit)? = null
    private var releasing = false
    private var releaseFailed = false
    val canControl get() = !releasing && !releaseFailed

    override fun onServiceConnected() {
        super.onServiceConnected()
        app.accessibility = this
        app.host.accessibilityChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) app.host.checkControlWindow()
    }

    override fun onInterrupt() {
        app.host.stop("Android interrupted the manual input service.")
        releaseInputs()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun detach() {
        releaseInputs()
        if (app.accessibility === this) {
            app.accessibility = null
            app.host.accessibilityChanged()
        }
    }

    fun safeTarget(packageName: String): Boolean {
        if (!canControl) return false
        val lock = getSystemService(KeyguardManager::class.java)
        if (!getSystemService(PowerManager::class.java).isInteractive || lock.isKeyguardLocked || lock.isDeviceLocked) return false
        val current = windows
        val active = current.singleOrNull { it.isActive && it.displayId == Display.DEFAULT_DISPLAY } ?: return false
        if (active.type != AccessibilityWindowInfo.TYPE_APPLICATION || !active.isFocused ||
            current.any { it.displayId == Display.DEFAULT_DISPLAY && it.layer > active.layer &&
                (it.isActive || it.isFocused || it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                    it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) }) return false
        val root = active.root ?: return false
        val accepted = root.packageName?.toString() == packageName && root.windowId == active.id &&
            packageName != this.packageName
        if (Build.VERSION.SDK_INT < 33) recycleLegacy(root)
        return accepted
    }

    private fun safePoint(point: Point): Boolean {
        val current = windows
        val active = current.singleOrNull { it.isActive && it.displayId == Display.DEFAULT_DISPLAY } ?: return false
        val bounds = Rect()
        active.getBoundsInScreen(bounds)
        if (!bounds.contains(point.x.toInt(), point.y.toInt())) return false
        return current.none {
            if (it.displayId != Display.DEFAULT_DISPLAY || it.layer <= active.layer) false
            else {
                val overlay = Rect()
                it.getBoundsInScreen(overlay)
                overlay.contains(point.x.toInt(), point.y.toInt())
            }
        }
    }

    fun execute(event: Packet.Input, geometry: Geometry, authorized: () -> Boolean, done: (Boolean) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (completion != null || !canControl) { done(false); return }
        try {
            val point = geometry.hostPoint(Point(event.x, event.y))
            if (event.kind != InputKind.RELEASE && !safePoint(point)) { done(false); return }
            if (!authorized()) { done(false); return }
            when (event.kind) {
                InputKind.DOWN -> {
                    if (stroke != null) { done(false); return }
                    dispatch(GestureDescription.StrokeDescription(path(point), 0, 16, true), true, point, done)
                }
                InputKind.MOVE, InputKind.UP -> {
                    val held = stroke
                    val previous = lastPoint
                    if (held == null || previous == null) { done(false); return }
                    val keep = event.kind == InputKind.MOVE
                    val next = held.continueStroke(path(previous, point), 0, 16, keep)
                    dispatch(next, keep, point, done)
                }
                InputKind.RELEASE -> {
                    val held = stroke
                    val pointAtRest = lastPoint
                    if (held == null || pointAtRest == null) done(true)
                    else dispatch(held.continueStroke(path(pointAtRest), 0, 1, false), false, pointAtRest, done)
                }
            }
        } catch (error: IllegalArgumentException) {
            reject(error, done)
        } catch (error: IllegalStateException) {
            reject(error, done)
        } catch (error: SecurityException) {
            reject(error, done)
        }
    }

    private fun reject(error: Exception, done: (Boolean) -> Unit) {
        Log.w("GameLink", "Android rejected an owned manual gesture", error)
        completion = null
        releaseInputs()
        done(false)
    }

    private fun dispatch(
        next: GestureDescription.StrokeDescription, keep: Boolean, end: Point, done: (Boolean) -> Unit,
    ) {
        val owner = ++revision
        completion = done
        val accepted = dispatchGesture(description(next), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = finish(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = finish(false)
            private fun finish(success: Boolean) {
                if (owner != revision) return
                main.removeCallbacks(gestureDeadline)
                val callback = completion
                completion = null
                callback?.invoke(success)
            }
        }, main)
        if (!accepted) {
            completion = null
            releaseInputs()
            done(false)
        } else {
            stroke = if (keep) next else null
            lastPoint = if (keep) end else null
            main.postDelayed(gestureDeadline, 250)
        }
    }

    private val gestureDeadline = Runnable {
        app.host.stop("Android did not complete the manual gesture in time.")
        releaseInputs()
    }

    fun releaseInputs() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (stroke == null && completion == null) return
        val owner = ++revision
        main.removeCallbacks(gestureDeadline)
        val held = stroke
        val point = lastPoint
        val pending = completion
        stroke = null
        lastPoint = null
        completion = null
        if (held != null && point != null) {
            releasing = true
            app.host.accessibilityChanged()
            try {
                // Public Accessibility has no raw ACTION_CANCEL. End only our own continued stroke;
                // never synthesize a replacement tap elsewhere to try to cancel it.
                val end = held.continueStroke(path(point), 0, 1, false)
                val accepted = dispatchGesture(description(end), object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (owner == revision) {
                            main.removeCallbacks(releaseDeadline)
                            releasing = false
                            app.host.accessibilityChanged()
                        }
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (owner == revision) reportReleaseFailure()
                    }
                }, main)
                if (!accepted) reportReleaseFailure() else main.postDelayed(releaseDeadline, 250)
            } catch (error: IllegalArgumentException) {
                Log.w("GameLink", "Owned stroke could not be continued for release", error)
                reportReleaseFailure()
            } catch (error: IllegalStateException) {
                Log.w("GameLink", "Gesture service unavailable during release", error)
                reportReleaseFailure()
            } catch (error: SecurityException) {
                Log.w("GameLink", "Android denied gesture release", error)
                reportReleaseFailure()
            }
        }
        pending?.invoke(false)
    }

    private val releaseDeadline = Runnable {
        if (releasing) reportReleaseFailure()
    }

    private fun reportReleaseFailure() {
        main.removeCallbacks(releaseDeadline)
        releasing = false
        releaseFailed = true
        app.host.accessibilityChanged()
        main.post {
            app.host.stop("Android did not confirm gesture release.")
            app.host.report("Control is disabled. Check the Host locally; manually restart Accessibility before another control grant.")
        }
    }

    private fun description(stroke: GestureDescription.StrokeDescription) =
        GestureDescription.Builder().setDisplayId(Display.DEFAULT_DISPLAY).addStroke(stroke).build()

    private fun path(start: Point, end: Point? = null) = Path().apply {
        moveTo(start.x, start.y)
        if (end != null) lineTo(end.x, end.y)
    }

    @Suppress("DEPRECATION")
    private fun recycleLegacy(node: android.view.accessibility.AccessibilityNodeInfo) = node.recycle()
}
