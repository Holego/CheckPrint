package io.github.holego.checkprint

import io.github.holego.checkprint.escpos.CodePage
import io.github.holego.checkprint.escpos.EscPos
import io.github.holego.checkprint.escpos.MonoBitmap
import io.github.holego.checkprint.escpos.TextEncoder
import io.github.holego.checkprint.net.IpRange
import io.github.holego.checkprint.net.NetworkScanner
import io.github.holego.checkprint.net.PrinterService
import io.github.holego.checkprint.net.Protocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IpRangeTest {
    @Test
    fun cidr24SkipsNetworkAndBroadcast() {
        val ips = IpRange.expand("192.168.1.0/24")
        assertEquals(254, ips.size)
        assertEquals("192.168.1.1", ips.first())
        assertEquals("192.168.1.254", ips.last())
    }

    @Test
    fun cidrWithHostBitsIsNormalised() {
        assertEquals("10.0.0.1", IpRange.expand("10.0.0.37/24").first())
        assertEquals("192.168.1.0/24", IpRange.cidrOf("192.168.1.37", 24))
    }

    @Test
    fun lastOctetRange() {
        val ips = IpRange.expand("192.168.1.10-12")
        assertEquals(listOf("192.168.1.10", "192.168.1.11", "192.168.1.12"), ips)
    }

    @Test
    fun fullRangeAndSingleAddressesCombine() {
        val ips = IpRange.expand("10.0.0.254-10.0.1.1, 172.16.0.5")
        assertEquals(listOf("10.0.0.254", "10.0.0.255", "10.0.1.0", "10.0.1.1", "172.16.0.5"), ips)
    }

    @Test
    fun rejectsGarbageAndOversizedRanges() {
        assertThrows(IllegalArgumentException::class.java) { IpRange.expand("printer") }
        assertThrows(IllegalArgumentException::class.java) { IpRange.expand("192.168.1.300") }
        assertThrows(IllegalArgumentException::class.java) { IpRange.expand("") }
        assertThrows(IpRange.RangeTooLargeException::class.java) { IpRange.expand("10.0.0.0/8") }
    }

    @Test
    fun portsParse() {
        assertEquals(listOf(9100, 8100, 631), NetworkScanner.parsePorts("9100, 8100;631 9100"))
        assertThrows(IllegalArgumentException::class.java) { NetworkScanner.parsePorts("9100, abc") }
        assertThrows(IllegalArgumentException::class.java) { NetworkScanner.parsePorts("70000") }
    }

    @Test
    fun serviceAndProtocolGuesses() {
        assertEquals(PrinterService.RAW, PrinterService.forPort(8100))
        assertEquals(PrinterService.IPP, PrinterService.forPort(631))
        assertEquals(Protocol.LPD, Protocol.forPort(515))
        assertEquals(Protocol.RAW, Protocol.forPort(9100))
    }
}

class EscPosTest {
    @Test
    fun basicCommands() {
        assertArrayEquals(byteArrayOf(0x1B, 0x40), EscPos.init())
        assertArrayEquals(byteArrayOf(0x1B, 0x74, 17), EscPos.codePage(17))
        assertArrayEquals(byteArrayOf(0x1B, 0x61, 1), EscPos.align(1))
        assertArrayEquals(byteArrayOf(0x1D, 0x21, 0x11), EscPos.charSize(2, 2))
        assertArrayEquals(byteArrayOf(0x1D, 0x21, 0x70), EscPos.charSize(8, 1))
        assertArrayEquals(byteArrayOf(0x1D, 0x21, 0x07), EscPos.charSize(0, 99)) // clamped to 1..8
        assertArrayEquals(byteArrayOf(0x1D, 0x56, 66, 0), EscPos.cut())
    }

    @Test
    fun rasterIsSplitIntoBands() {
        // 16 px wide (2 bytes per row), 300 rows -> bands of 128, 128, 44
        val img = MonoBitmap(16, 300, ByteArray(2 * 300) { 0xAA.toByte() })
        val data = EscPos.raster(img, bandRows = 128)
        assertEquals(3 * 8 + 2 * 300, data.size)
        // first header: GS v 0 m xL xH yL yH
        assertArrayEquals(byteArrayOf(0x1D, 0x76, 0x30, 0, 2, 0, 128.toByte(), 0), data.copyOfRange(0, 8))
        val lastHeader = 2 * 8 + 2 * 256
        assertArrayEquals(byteArrayOf(0x1D, 0x76, 0x30, 0, 2, 0, 44, 0), data.copyOfRange(lastHeader, lastHeader + 8))
    }
}

class TextEncoderTest {
    @Test
    fun cyrillicInCp866() {
        val bytes = TextEncoder.encode("Привет", CodePage.CP866)
        assertArrayEquals(byteArrayOf(0x8F.toByte(), 0xE0.toByte(), 0xA8.toByte(), 0xA2.toByte(), 0xA5.toByte(), 0xE2.toByte()), bytes)
    }

    @Test
    fun cyrillicInCp1251() {
        val bytes = TextEncoder.encode("Ёж", CodePage.CP1251)
        assertArrayEquals(byteArrayOf(0xA8.toByte(), 0xE6.toByte()), bytes)
    }

    @Test
    fun newlinesAreNormalisedAndAsciiPassesThrough() {
        val bytes = TextEncoder.encode("a\r\nb\rc", CodePage.CP437)
        assertArrayEquals("a\nb\nc".toByteArray(Charsets.US_ASCII), bytes)
    }

    @Test
    fun utf8KeepsEverything() {
        assertTrue(TextEncoder.encode("Привет €", CodePage.UTF8).size > 8)
    }
}
