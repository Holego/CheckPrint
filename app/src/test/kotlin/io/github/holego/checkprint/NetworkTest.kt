package io.github.holego.checkprint

import io.github.holego.checkprint.net.NetworkScanner
import io.github.holego.checkprint.net.PrinterTransport
import io.github.holego.checkprint.net.Protocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Fake printers on localhost so the scanner and transports are exercised end to end. */
class NetworkTest {
    private fun rawPrinter(received: ByteArrayOutputStream): ServerSocket {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            server.accept().use { s ->
                val buf = ByteArray(4096)
                while (true) {
                    val n = s.getInputStream().read(buf)
                    if (n < 0) break
                    received.write(buf, 0, n)
                }
            }
        }
        return server
    }

    @Test
    fun scannerFindsOpenPortAndSkipsClosedOne() = runBlocking {
        val open = ServerSocket(0)
        val openPort = open.localPort
        val closedPort = ServerSocket(0).let { val p = it.localPort; it.close(); p }
        val found = mutableListOf<Int>()
        val counter = AtomicInteger()
        NetworkScanner.scan(listOf("127.0.0.1"), listOf(openPort, closedPort), 500, counter) { _, port ->
            synchronized(found) { found += port }
        }
        open.close()
        assertEquals(2, counter.get())
        assertEquals(listOf(openPort), found)
    }

    @Test
    fun rawTransportDeliversBytesVerbatim() = runBlocking {
        val received = ByteArrayOutputStream()
        val server = rawPrinter(received)
        val payload = ByteArray(70_000) { (it % 251).toByte() } // bigger than any socket buffer
        PrinterTransport.send(Protocol.RAW, "127.0.0.1", server.localPort, payload)
        Thread.sleep(200)
        server.close()
        assertArrayEquals(payload, received.toByteArray())
    }

    @Test
    fun lpdTransportFollowsRfc1179Handshake() = runBlocking {
        val server = ServerSocket(0)
        val log = ByteArrayOutputStream()
        val dataFile = ByteArrayOutputStream()
        thread(isDaemon = true) {
            try { server.accept().use { s ->
                val inp = s.getInputStream()
                val out = s.getOutputStream()
                fun readLine(): String {
                    val sb = StringBuilder()
                    while (true) {
                        val c = inp.read()
                        if (c < 0 || c == '\n'.code) break
                        sb.append(c.toChar())
                    }
                    return sb.toString()
                }
                // 02 queue LF
                assertEquals(2, inp.read()); log.write(readLine().toByteArray()); out.write(0); out.flush()
                // 02 size SP cfA... LF ; control bytes ; 00
                assertEquals(2, inp.read())
                val cf = readLine(); log.write(cf.toByteArray())
                val cfSize = cf.substringBefore(' ').toInt()
                out.write(0); out.flush()
                repeat(cfSize) { inp.read() }
                assertEquals(0, inp.read()); out.write(0); out.flush()
                // 03 size SP dfA... LF ; data ; 00
                assertEquals(3, inp.read())
                val df = readLine()
                val dfSize = df.substringBefore(' ').toInt()
                out.write(0); out.flush()
                repeat(dfSize) { dataFile.write(inp.read()) }
                assertEquals(0, inp.read()); out.write(0); out.flush()
            } } catch (t: Throwable) { System.err.println("FAKE LPD SERVER: " + t); t.printStackTrace() }
        }
        val payload = byteArrayOf(0x68, 0x65, 0x1B, 0x00, 0x03, 0x6C, 0x6F) // binary-safe: ESC/NUL/ETX pass through
        PrinterTransport.send(Protocol.LPD, "127.0.0.1", server.localPort, payload, "receipts")
        server.close()
        assertArrayEquals(payload, dataFile.toByteArray())
        assertTrue(log.toString().startsWith("receipts"))
    }
}
