package dev.rainradar.sg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime

class RadarTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun sgt(h: Int, m: Int, s: Int = 0) = ZonedDateTime.of(2026, 11, 25, h, m, s, 0, Radar.SGT)

    // --- frame timing ------------------------------------------------------------------

    @Test
    fun slotRoundsDownToFiveMinutes() {
        assertEquals(sgt(10, 20), Radar.slotFor(sgt(10, 24, 59)))
        assertEquals(sgt(10, 20), Radar.slotFor(sgt(10, 20, 30)))
    }

    @Test
    fun slotOnTheBoundaryIsUnchanged() {
        assertEquals(sgt(10, 25), Radar.slotFor(sgt(10, 25)))
    }

    @Test
    fun slotConvertsToSingaporeTime() {
        val utc = ZonedDateTime.of(2026, 11, 24, 18, 7, 0, 0, ZoneOffset.UTC)
        val slot = Radar.slotFor(utc)
        assertEquals(Radar.SGT, slot.zone)
        assertEquals(sgt(2, 5), slot)
    }

    @Test
    fun frameUrlUsesTheSlotTimestamp() {
        assertEquals(
            "https://www.weather.gov.sg/files/rainarea/50km/v2/dpsri_70km_2026112510200000dBR.dpsri.png",
            Radar.frameUrl(sgt(10, 20)),
        )
    }

    @Test
    fun clockIsLowercaseTwelveHour() {
        assertEquals("10:20 am", Radar.clock(sgt(10, 20)))
        assertEquals("3:05 pm", Radar.clock(sgt(15, 5)))
        assertEquals("12:00 am", Radar.clock(sgt(0, 0)))
    }

    // --- geography ---------------------------------------------------------------------

    @Test
    fun singaporeLandmarksAreOnTheMap() {
        assertTrue(Radar.onMap(1.2801, 103.8167)) // Marina Bay
        assertTrue(Radar.onMap(1.3521, 103.8198)) // centre of the island
    }

    @Test
    fun placesOutsideTheMapAreRejected() {
        assertFalse(Radar.onMap(1.5500, 103.8000)) // Johor Bahru, north of the map
        assertFalse(Radar.onMap(1.3000, 104.2000)) // east of the map
        assertFalse(Radar.onMap(-1.3, 103.8)) // southern hemisphere
    }

    @Test
    fun mapCornersMatchImageEdges() {
        assertEquals(0f, Radar.mapX(Radar.lonAt(0f)), 1e-3f)
        assertEquals(Radar.MAP_W.toFloat(), Radar.mapX(Radar.lonAt(Radar.MAP_W.toFloat())), 1e-3f)
        assertEquals(0f, Radar.mapY(Radar.latAt(0f)), 1e-3f)
        assertEquals(Radar.MAP_H.toFloat(), Radar.mapY(Radar.latAt(Radar.MAP_H.toFloat())), 1e-3f)
    }

    @Test
    fun northIsUpAndEastIsRight() {
        assertTrue(Radar.mapY(1.40) < Radar.mapY(1.30))
        assertTrue(Radar.mapX(103.9) > Radar.mapX(103.7))
    }

    @Test
    fun pixelsRoundTripToTheSamePlace() {
        val lat = 1.3521
        val lon = 103.8198
        assertEquals(lat, Radar.latAt(Radar.mapY(lat)), 1e-5)
        assertEquals(lon, Radar.lonAt(Radar.mapX(lon)), 1e-5)
    }

    @Test
    fun metersPerPixelIsAboutSeventyFive() {
        // ~36 km of latitude over 479 px.
        assertEquals(75.2, Radar.METERS_PER_PX, 0.5)
    }

    // --- file writes -------------------------------------------------------------------

    @Test
    fun writeAtomicReplacesFileAndLeavesNoTemp() {
        val f = File(tmp.root, "frame.png")
        f.writeBytes(byteArrayOf(1, 2, 3))
        Radar.writeAtomic(f, byteArrayOf(9, 8))
        assertArrayEquals(byteArrayOf(9, 8), f.readBytes())
        assertFalse(File(tmp.root, "frame.png.tmp").exists())
    }
}
