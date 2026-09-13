package io.github.holego.checkprint.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL

enum class Protocol {
    RAW, LPD, IPP;

    companion object {
        fun forPort(port: Int): Protocol = when (port) {
            515 -> LPD
            631 -> IPP
            else -> RAW
        }
    }
}

/** Delivers an already-built ESC/POS byte stream to a network printer. */
object PrinterTransport {
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 10_000

    suspend fun send(protocol: Protocol, host: String, port: Int, data: ByteArray, lpdQueue: String = "lp") =
        withContext(Dispatchers.IO) {
            when (protocol) {
                Protocol.RAW -> sendRaw(host, port, data)
                Protocol.LPD -> sendLpd(host, port, lpdQueue, data)
                Protocol.IPP -> sendIpp(host, port, data)
            }
        }

    /** JetDirect style: open the socket, write the bytes, close. This is what port 9100/8100 expects. */
    private fun sendRaw(host: String, port: Int, data: ByteArray) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = 500
            val out = socket.getOutputStream()
            out.write(data)
            out.flush()
            socket.shutdownOutput()
            // Some cheap firmwares drop the tail of a job if the client vanishes instantly; a short
            // grace read gives them time to drain. Either a byte or a timeout is fine.
            try {
                socket.getInputStream().read()
            } catch (e: IOException) {
                // expected: printers rarely answer
            }
        }
    }

    /** RFC 1179 line printer daemon: control file, then data file, each acknowledged with a zero byte. */
    private fun sendLpd(host: String, port: Int, queue: String, data: ByteArray) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val out = BufferedOutputStream(socket.getOutputStream())
            val input = socket.getInputStream()

            fun ack(step: String) {
                val code = input.read()
                if (code != 0) throw IOException("LPD: $step rejected (code $code)")
            }

            val jobId = (System.currentTimeMillis() % 1000).toString().padStart(3, '0')
            val clientHost = "checkprint"
            val q = queue.trim().ifEmpty { "lp" }

            out.write(0x02) // receive a printer job
            out.write("$q\n".toByteArray(Charsets.US_ASCII))
            out.flush()
            ack("receive job")

            val control = ("H$clientHost\nPcheckprint\n" +
                "ldfA$jobId$clientHost\nUdfA$jobId$clientHost\nNcheckprint.bin\n")
                .toByteArray(Charsets.US_ASCII)
            out.write(0x02) // receive control file
            out.write("${control.size} cfA$jobId$clientHost\n".toByteArray(Charsets.US_ASCII))
            out.flush()
            ack("control file header")
            out.write(control)
            out.write(0)
            out.flush()
            ack("control file")

            out.write(0x03) // receive data file
            out.write("${data.size} dfA$jobId$clientHost\n".toByteArray(Charsets.US_ASCII))
            out.flush()
            ack("data file header")
            out.write(data)
            out.write(0)
            out.flush()
            ack("data file")
        }
    }

    /** Minimal IPP Print-Job (RFC 8011) with application/octet-stream, tried on the usual resource paths. */
    private fun sendIpp(host: String, port: Int, data: ByteArray) {
        val paths = listOf("/ipp/print", "/ipp", "/", "/printers/lp")
        var lastError: IOException? = null
        for (path in paths) {
            try {
                val body = buildPrintJob("ipp://$host:$port$path", data)
                val conn = URL("http://$host:$port$path").openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = CONNECT_TIMEOUT_MS
                    conn.readTimeout = READ_TIMEOUT_MS
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/ipp")
                    conn.setFixedLengthStreamingMode(body.size)
                    conn.outputStream.use { it.write(body) }
                    val http = conn.responseCode
                    if (http == 404 || http == 405) {
                        lastError = IOException("IPP: HTTP $http at $path")
                        continue
                    }
                    if (http != 200) throw IOException("IPP: HTTP $http")
                    val response = conn.inputStream.use { it.readBytes() }
                    if (response.size < 8) throw IOException("IPP: short response")
                    val status = ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF)
                    // 0x0000-0x0002 are the successful-ok family.
                    if (status > 0x0002) throw IOException("IPP: status 0x" + status.toString(16).padStart(4, '0'))
                    return
                } finally {
                    conn.disconnect()
                }
            } catch (e: ConnectException) {
                throw e
            } catch (e: SocketTimeoutException) {
                throw e
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("IPP: no printer endpoint answered")
    }

    private fun buildPrintJob(printerUri: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + 256)
        fun u16(v: Int) {
            out.write((v shr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        fun attr(tag: Int, name: String, value: String) {
            out.write(tag)
            val n = name.toByteArray(Charsets.UTF_8)
            u16(n.size)
            out.write(n)
            val v = value.toByteArray(Charsets.UTF_8)
            u16(v.size)
            out.write(v)
        }
        out.write(1); out.write(1)          // IPP/1.1
        u16(0x0002)                         // Print-Job
        out.write(0); out.write(0); out.write(0); out.write(1) // request-id
        out.write(0x01)                     // operation-attributes-tag
        attr(0x47, "attributes-charset", "utf-8")
        attr(0x48, "attributes-natural-language", "en")
        attr(0x45, "printer-uri", printerUri)
        attr(0x42, "requesting-user-name", "checkprint")
        attr(0x42, "job-name", "CheckPrint")
        attr(0x49, "document-format", "application/octet-stream")
        out.write(0x03)                     // end-of-attributes-tag
        out.write(data)
        return out.toByteArray()
    }
}
