package pl.linuch.ducatitelemetry

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Document
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

class GpxExporterTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val utc = Instant.parse("2026-08-22T16:24:31.250Z").toEpochMilli()
    private fun session() = RideSession("ride", utc, utc, 1, RideSessionState.COMPLETED,
        "ride.csv", false, 0)
    private fun csv(vararg rows: String): File = temporaryFolder.newFile("ride.csv").apply {
        writeText(TelemetryCsv.HEADER + "\n" + rows.joinToString("\n"))
    }
    private fun data(vararg points: GpxPoint): File = temporaryFolder.newFile("ride.gpxdata").apply {
        writeText(GpxPointData.HEADER + "\n" + points.joinToString("\n", transform = GpxPointData::row))
    }
    private fun point(altitude: Double? = 284.2) = GpxPoint(123L, utc, 50.1234567, 18.9876543,
        altitude, 6840, 82.4, 79.8, 4, 67.5, 0.0, 87, 23, -38.2, -12.4, 3.2)
    private fun export(csv: File, data: File?) = ByteArrayOutputStream().also {
        GpxExporter().export(csv, data, session(), it)
    }.toString(Charsets.UTF_8.name())
    private fun parse(xml: String): Document = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(xml.byteInputStream())

    @Test fun validGpx11ParsesAndContainsMetadataAndTrackPoint() {
        val xml = export(csv(), data(point()))
        val document = parse(xml)
        assertEquals("1.1", document.documentElement.getAttribute("version"))
        assertEquals("http://www.topografix.com/GPX/1/1", document.documentElement.namespaceURI)
        assertEquals(1, document.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "metadata").length)
        assertEquals(1, document.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkpt").length)
        assertTrue(xml.contains("2026-08-22T16:24:31.250Z"))
    }

    @Test fun outputIsLocaleIndependentAndMissingAltitudeIsOmitted() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val xml = export(csv(), data(point(null)))
            assertTrue(xml.contains("lat=\"50.1234567\" lon=\"18.9876543\""))
            assertFalse(xml.contains("<ele>"))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun extensionsPreserveSeparateSpeedsAndSignedLean() {
        val xml = export(csv(), data(point()))
        assertTrue(xml.contains("xmlns:ducati=\"${GpxExporter.EXTENSION_NAMESPACE}\""))
        assertTrue(xml.contains("<ducati:can_speed_kmh>82.4</ducati:can_speed_kmh>"))
        assertTrue(xml.contains("<ducati:gps_speed_kmh>79.8</ducati:gps_speed_kmh>"))
        assertTrue(xml.contains("<ducati:lean_angle_deg>-38.2</ducati:lean_angle_deg>"))
        assertTrue(xml.contains("<ducati:rpm>6840</ducati:rpm>"))
    }

    @Test fun missingExtensionValuesAreOmitted() {
        val sparse = point().copy(rollRateDps = null, gpsSpeedKmh = null)
        val xml = export(csv(), data(sparse))
        assertFalse(xml.contains("roll_rate_dps"))
        assertFalse(xml.contains("gps_speed_kmh"))
    }

    @Test(expected = NoGpxDataException::class)
    fun rideWithoutGnssCannotExport() { export(csv(), null) }

    @Test fun legacyRepeatedCanRowsProduceOnePoint() {
        fun row(time: Long) = "$time,1,1,3000,2,42.00,10.00,0.00,80,20,50.1234567,18.9876543,284.20,79.80,10.00,3.20,-38.20,-12.40,3"
        val points = GpxExporter().loadPoints(csv(row(utc), row(utc + 20)), null)
        assertEquals(1, points.size)
    }

    @Test fun timestampGapCreatesNoSyntheticPoints() {
        val second = point().copy(gnssTimestampNanos = 999L, utcTimestampMs = utc + 60_000, latitude = 50.2)
        val xml = export(csv(), data(point(), second))
        assertEquals(2, parse(xml).getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkpt").length)
    }
}
