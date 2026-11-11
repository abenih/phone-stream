package dev.gamecontrols.link.protocol

import org.junit.Assert.*
import org.junit.Test

class GeometryTest {
    @Test fun s10LandscapeIntoPortraitA06RejectsOuterBars() {
        val geometry = Geometry(3040, 1440, 1280, 606, 90)
        val rect = geometry.contentRect(720, 1600)
        assertTrue(rect.top > 600)
        assertNull(geometry.mapTouch(360f, 100f, 720, 1600))
        assertEquals(Point(0.5f, 0.5f), geometry.mapTouch(360f, 800f, 720, 1600))
    }

    @Test fun alignedEncoderInnerBarsAreNotClickable() {
        val geometry = Geometry(3040, 1440, 1280, 592, 90)
        val video = geometry.videoRect(1280, 592)
        val content = geometry.contentRect(1280, 592)
        assertTrue(content.left > video.left)
        assertNull(geometry.mapTouch(0f, 296f, 1280, 592))
        assertPoint(Point(0f, 0f), geometry.mapTouch(content.left, content.top, 1280, 592))
        assertPoint(Point(1f, 1f), geometry.mapTouch(content.right, content.bottom, 1280, 592))
    }

    @Test fun allRotationsMapCurrentDisplayWithoutDoubleRotation() {
        listOf(0, 90, 180, 270).forEach { rotation ->
            val geometry = if (rotation % 180 == 0) Geometry(1440, 3040, 606, 1280, rotation)
                else Geometry(3040, 1440, 1280, 606, rotation)
            val box = geometry.contentRect(1600, 720)
            assertPoint(Point(0.25f, 0.75f), geometry.mapTouch(
                box.left + box.width * 0.25f, box.top + box.height * 0.75f, 1600, 720))
            assertEquals(Point(0f, 0f), geometry.hostPoint(Point(0f, 0f)))
            assertEquals(Point((geometry.displayWidth - 1).toFloat(), (geometry.displayHeight - 1).toFloat()),
                geometry.hostPoint(Point(1f, 1f)))
        }
    }

    @Test fun invalidViewsAndNonFiniteTouchesNeverClampToEdges() {
        val geometry = Geometry(100, 200, 100, 200, 0)
        assertNull(geometry.mapTouch(Float.NaN, 2f, 100, 200))
        assertNull(geometry.mapTouch(2f, Float.POSITIVE_INFINITY, 100, 200))
        assertNull(geometry.mapTouch(-1f, 2f, 100, 200))
        assertNull(geometry.mapTouch(101f, 2f, 100, 200))
        assertNull(geometry.mapTouch(2f, 2f, 0, 0))
        assertThrows(IllegalArgumentException::class.java) { geometry.hostPoint(Point(-0.1f, 0f)) }
    }

    @Test fun geometryRejectsInvalidEpochRotationAndDimensions() {
        assertThrows(IllegalArgumentException::class.java) { Geometry(0, 200, 100, 200, 0) }
        assertThrows(IllegalArgumentException::class.java) { Geometry(100, 200, 99, 200, 0) }
        assertThrows(IllegalArgumentException::class.java) { Geometry(100, 200, 100, 200, 45) }
        assertThrows(IllegalArgumentException::class.java) { Geometry(100, 200, 100, 200, 0, 0) }
    }

    @Test fun encodeSizeHonorsCodecAlignmentBoundsAndBothOrientations() {
        listOf(1440 to 3040, 3040 to 1440, 721 to 1601, 1000 to 1000).forEach { (w, h) ->
            listOf(2, 8, 16).forEach { alignment ->
                val (encodedW, encodedH) = EncodeSize.fit(w, h, 1280, alignment, alignment)
                assertEquals(0, encodedW % alignment)
                assertEquals(0, encodedH % alignment)
                assertTrue(maxOf(encodedW, encodedH) <= 1280)
                val geometry = Geometry(w, h, encodedW, encodedH, 0)
                val box = geometry.contentRect(1000, 1000)
                assertEquals(w.toFloat() / h, box.width / box.height, 0.0001f)
            }
        }
    }

    @Test fun invalidEncodeProfilesAreExplicitErrors() {
        assertThrows(IllegalArgumentException::class.java) { EncodeSize.fit(0, 100) }
        assertThrows(IllegalArgumentException::class.java) { EncodeSize.fit(100, 100, 8192) }
        assertThrows(IllegalArgumentException::class.java) { EncodeSize.fit(100, 100, widthAlignment = 0) }
        assertThrows(IllegalArgumentException::class.java) { EncodeSize.fit(2, 8192) }
    }

    private fun assertPoint(expected: Point, actual: Point?) {
        requireNotNull(actual)
        assertEquals(expected.x, actual.x, 0.00001f)
        assertEquals(expected.y, actual.y, 0.00001f)
    }
}
