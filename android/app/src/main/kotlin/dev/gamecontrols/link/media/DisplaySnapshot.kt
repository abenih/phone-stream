package dev.gamecontrols.link.media

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager

data class DisplaySnapshot(val width: Int, val height: Int, val rotation: Int, val density: Int) {
    companion object {
        fun read(context: Context): DisplaySnapshot {
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                ?: error("Default display is unavailable.")
            val displayContext = context.createDisplayContext(display)
            val bounds = displayContext.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            return DisplaySnapshot(bounds.width(), bounds.height(), display.rotation * 90,
                displayContext.resources.configuration.densityDpi)
        }
    }
}
