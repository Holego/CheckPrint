package io.github.holego.checkprint.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** What a given open port most likely speaks. Used only for labelling and picking a default protocol. */
enum class PrinterService {
    RAW, IPP, LPD, HTTP, EPOS, OTHER;

    companion object {
        fun forPort(port: Int): PrinterService = when (port) {
            9100, 9101, 9102, 8100, 8000, 4000 -> RAW
            631 -> IPP
            515 -> LPD
            80, 443, 8080 -> HTTP
            8008, 8043 -> EPOS
            else -> OTHER
        }
    }
}

data class FoundHost(
    val ip: String,
    val hostname: String? = null,
    val ports: List<Int> = emptyList(),
)

/** Plain TCP connect scan: a port that accepts a connection within the timeout counts as open. */
object NetworkScanner {
    /** 9100-9102 raw/JetDirect, 8100 (some Chinese ESC/POS printers), 8008 Epson ePOS, 631 IPP, 515 LPD. */
    val DEFAULT_PORTS = listOf(9100, 9101, 9102, 8100, 8008, 631, 515)

    fun parsePorts(text: String): List<Int> =
        text.split(',', ';', ' ', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.toIntOrNull()?.takeIf { p -> p in 1..65535 } ?: throw IllegalArgumentException(it) }
            .distinct()

    /**
     * Probes every ip x port pair with at most [concurrency] connections in flight.
     * [counter] is bumped after each probe so the UI can show progress without touching state from
     * worker threads; [onOpen] runs inside the scan scope for each open port.
     */
    suspend fun scan(
        ips: List<String>,
        ports: List<Int>,
        timeoutMs: Int,
        counter: AtomicInteger,
        concurrency: Int = 48,
        onOpen: suspend (ip: String, port: Int) -> Unit,
    ) {
        val semaphore = Semaphore(concurrency)
        coroutineScope {
            for (ip in ips) {
                for (port in ports) {
                    launch(Dispatchers.IO) {
                        semaphore.withPermit {
                            val open = isOpen(ip, port, timeoutMs)
                            counter.incrementAndGet()
                            if (open) onOpen(ip, port)
                        }
                    }
                }
            }
        }
    }

    fun isOpen(ip: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            true
        }
    } catch (e: IOException) {
        false
    } catch (e: SecurityException) {
        false
    }

    /** Reverse DNS with a hard timeout; returns null when the printer has no name. */
    suspend fun resolveHostname(ip: String): String? = withTimeoutOrNull(2_000) {
        runInterruptible(Dispatchers.IO) {
            runCatching { InetAddress.getByName(ip).canonicalHostName }
                .getOrNull()
                ?.takeIf { it.isNotBlank() && it != ip }
        }
    }
}
