package io.github.holego.checkprint

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.holego.checkprint.escpos.CodePage
import io.github.holego.checkprint.escpos.EscPos
import io.github.holego.checkprint.escpos.ImageRasterizer
import io.github.holego.checkprint.escpos.MonoBitmap
import io.github.holego.checkprint.escpos.TextEncoder
import io.github.holego.checkprint.net.FoundHost
import io.github.holego.checkprint.net.IpRange
import io.github.holego.checkprint.net.LocalNetwork
import io.github.holego.checkprint.net.NetworkScanner
import io.github.holego.checkprint.net.PrinterTransport
import io.github.holego.checkprint.net.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("checkprint", Context.MODE_PRIVATE)

    sealed class ScanError {
        data object BadRange : ScanError()
        data object BadPorts : ScanError()
        data object NoNetwork : ScanError()
        data class TooMany(val max: Int) : ScanError()
    }

    class BadTargetException : IllegalArgumentException("host or port missing")

    // ---------------------------------------------------------------- scan

    var interfaces by mutableStateOf(LocalNetwork.interfaces())
        private set
    var rangeText by mutableStateOf(prefs.getString(KEY_RANGE, null) ?: LocalNetwork.defaultRange() ?: "192.168.1.0/24")
    var portsText by mutableStateOf(prefs.getString(KEY_PORTS, null) ?: NetworkScanner.DEFAULT_PORTS.joinToString(", "))
    var timeoutMs by mutableIntStateOf(prefs.getInt(KEY_TIMEOUT, 400))
    var scanning by mutableStateOf(false)
        private set
    var progress by mutableIntStateOf(0)
        private set
    var total by mutableIntStateOf(0)
        private set
    var scanFinished by mutableStateOf(false)
        private set
    var scanError by mutableStateOf<ScanError?>(null)
        private set
    val hosts = mutableStateListOf<FoundHost>()
    private var scanJob: Job? = null

    fun refreshInterfaces() {
        interfaces = LocalNetwork.interfaces()
    }

    fun useMySubnet() {
        refreshInterfaces()
        LocalNetwork.defaultRange()?.let { rangeText = it } ?: run { scanError = ScanError.NoNetwork }
    }

    fun resetPorts() {
        portsText = NetworkScanner.DEFAULT_PORTS.joinToString(", ")
    }

    fun startScan() {
        if (scanning) return
        scanError = null
        val ips = try {
            IpRange.expand(rangeText)
        } catch (e: IpRange.RangeTooLargeException) {
            scanError = ScanError.TooMany(IpRange.MAX_HOSTS); return
        } catch (e: IllegalArgumentException) {
            scanError = ScanError.BadRange; return
        }
        val ports = try {
            NetworkScanner.parsePorts(portsText)
        } catch (e: IllegalArgumentException) {
            scanError = ScanError.BadPorts; return
        }
        if (ports.isEmpty()) {
            scanError = ScanError.BadPorts; return
        }
        refreshInterfaces()
        if (interfaces.isEmpty()) scanError = ScanError.NoNetwork // warn, but still let the scan run

        prefs.edit().putString(KEY_RANGE, rangeText).putString(KEY_PORTS, portsText).putInt(KEY_TIMEOUT, timeoutMs).apply()

        hosts.clear()
        progress = 0
        total = ips.size * ports.size
        scanning = true
        scanFinished = false
        val counter = AtomicInteger()
        scanJob = viewModelScope.launch {
            val ticker = launch {
                while (isActive) {
                    progress = counter.get()
                    delay(100)
                }
            }
            try {
                NetworkScanner.scan(ips, ports, timeoutMs, counter) { ip, port ->
                    withContext(Dispatchers.Main) { addOpenPort(ip, port) }
                }
            } finally {
                ticker.cancel()
                progress = counter.get()
                scanning = false
                scanFinished = true
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
    }

    private fun addOpenPort(ip: String, port: Int) {
        val idx = hosts.indexOfFirst { it.ip == ip }
        if (idx >= 0) {
            val h = hosts[idx]
            if (port !in h.ports) hosts[idx] = h.copy(ports = (h.ports + port).sorted())
            return
        }
        val ipValue = IpRange.parseIp(ip)
        val insertAt = hosts.indexOfFirst { IpRange.parseIp(it.ip) > ipValue }.let { if (it < 0) hosts.size else it }
        hosts.add(insertAt, FoundHost(ip, null, listOf(port)))
        viewModelScope.launch {
            val name = NetworkScanner.resolveHostname(ip) ?: return@launch
            val i = hosts.indexOfFirst { it.ip == ip }
            if (i >= 0) hosts[i] = hosts[i].copy(hostname = name)
        }
    }

    // -------------------------------------------------------------- target

    var host by mutableStateOf(prefs.getString(KEY_HOST, "") ?: "")
    var port by mutableStateOf(prefs.getString(KEY_PORT, "9100") ?: "9100")
    var protocol by mutableStateOf(Protocol.RAW)
    var lpdQueue by mutableStateOf("lp")

    fun selectTarget(ip: String, p: Int) {
        host = ip
        port = p.toString()
        protocol = Protocol.forPort(p)
    }

    // ---------------------------------------------------------------- text

    var text by mutableStateOf("")
    var widthMul by mutableIntStateOf(1)
    var heightMul by mutableIntStateOf(1)
    var bold by mutableStateOf(false)
    var underline by mutableStateOf(false)
    var textAlign by mutableIntStateOf(0)
    var codePage by mutableStateOf(
        prefs.getString(KEY_CODEPAGE, null)?.let { name -> CodePage.entries.firstOrNull { it.name == name } } ?: CodePage.CP866
    )
    var escTOverride by mutableStateOf(prefs.getString(KEY_ESC_T, "") ?: "")

    fun selectCodePage(cp: CodePage) {
        codePage = cp
        escTOverride = ""
    }

    // --------------------------------------------------------------- image

    var imageUri by mutableStateOf<Uri?>(null)
        private set
    private var sourceBitmap: Bitmap? = null
    private var mono: MonoBitmap? = null
    var previewBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var imageError by mutableStateOf(false)
        private set
    var imageWidth by mutableIntStateOf(prefs.getInt(KEY_IMG_WIDTH, 384))
        private set
    var dither by mutableStateOf(true)
        private set
    var threshold by mutableIntStateOf(128)
        private set
    var imageAlign by mutableIntStateOf(1)
    private var previewJob: Job? = null

    fun setImage(uri: Uri?) {
        previewJob?.cancel()
        imageUri = uri
        sourceBitmap = null
        mono = null
        previewBitmap = null
        imageError = false
        if (uri == null) return
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { ImageRasterizer.load(getApplication<Application>().contentResolver, uri) }.getOrNull()
            }
            if (bmp == null) {
                imageError = true
                imageUri = null
                return@launch
            }
            sourceBitmap = bmp
            refreshPreview(immediate = true)
        }
    }

    fun updateImageWidth(w: Int) {
        imageWidth = w.coerceIn(8, 2048)
        refreshPreview()
    }

    fun updateDither(on: Boolean) {
        dither = on
        refreshPreview()
    }

    fun updateThreshold(t: Int) {
        threshold = t.coerceIn(1, 254)
        refreshPreview()
    }

    private fun refreshPreview(immediate: Boolean = false) {
        val src = sourceBitmap ?: return
        val w = imageWidth
        val d = dither
        val t = threshold
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            if (!immediate) delay(150) // coalesce slider drags
            val m = withContext(Dispatchers.Default) { ImageRasterizer.toMono(src, w, d, t) }
            val preview = withContext(Dispatchers.Default) { ImageRasterizer.toPreview(m) }
            mono = m
            previewBitmap = preview
        }
    }

    // ------------------------------------------------------------- options

    var imageFirst by mutableStateOf(true)
    var feedLines by mutableIntStateOf(3)
    var cut by mutableStateOf(true)
    var sending by mutableStateOf(false)
        private set

    private fun escTNumber(): Int = escTOverride.trim().toIntOrNull() ?: codePage.escT

    /** Returns null when there is nothing to print. */
    fun buildJob(): ByteArray? {
        val hasText = text.isNotBlank()
        val image = mono
        if (!hasText && image == null) return null

        val out = ByteArrayOutputStream()
        out.write(EscPos.init())
        val n = escTNumber()
        if (n in 0..255) out.write(EscPos.codePage(n))

        val textPart = {
            if (hasText) {
                out.write(EscPos.align(textAlign))
                out.write(EscPos.bold(bold))
                out.write(EscPos.underline(underline))
                out.write(EscPos.charSize(widthMul, heightMul))
                out.write(TextEncoder.encode(text, codePage))
                if (!text.endsWith("\n")) out.write(EscPos.lineFeed)
                out.write(EscPos.charSize(1, 1))
                out.write(EscPos.bold(false))
                out.write(EscPos.underline(false))
            }
        }
        val imagePart = {
            if (image != null) {
                out.write(EscPos.align(imageAlign))
                out.write(EscPos.raster(image))
            }
        }
        if (imageFirst) { imagePart(); textPart() } else { textPart(); imagePart() }
        finish(out)
        return out.toByteArray()
    }

    fun buildTestPage(title: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(EscPos.init())
        val n = escTNumber()
        if (n in 0..255) out.write(EscPos.codePage(n))
        fun line(s: String) {
            out.write(TextEncoder.encode(s, codePage))
            out.write(EscPos.lineFeed)
        }
        out.write(EscPos.align(1))
        out.write(EscPos.charSize(2, 2)); out.write(EscPos.bold(true))
        line("CheckPrint")
        out.write(EscPos.bold(false)); out.write(EscPos.charSize(1, 1))
        line(title)
        line("${host.trim()}:${port.trim()}  ${protocol.name}")
        line(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
        out.write(EscPos.align(0))
        line("--------------------------------")
        line("Left / Слева")
        out.write(EscPos.align(1)); line("Center / По центру")
        out.write(EscPos.align(2)); line("Right / Справа")
        out.write(EscPos.align(0))
        out.write(EscPos.bold(true)); line("Bold / Жирный"); out.write(EscPos.bold(false))
        out.write(EscPos.underline(true)); line("Underline / Подчеркнутый"); out.write(EscPos.underline(false))
        out.write(EscPos.charSize(2, 1)); line("Wide x2")
        out.write(EscPos.charSize(1, 2)); line("Tall x2")
        out.write(EscPos.charSize(2, 2)); line("Big x2")
        out.write(EscPos.charSize(3, 3)); line("x3")
        out.write(EscPos.charSize(1, 1))
        line("--------------------------------")
        line("Code page: ${codePage.label} (ESC t $n)")
        line("Привет, мир! ABC abc 0123456789")
        line("ЁёЙй №1 100% $#@ +-*/=")
        finish(out)
        return out.toByteArray()
    }

    private fun finish(out: ByteArrayOutputStream) {
        out.write(EscPos.align(0))
        if (feedLines > 0) out.write(EscPos.feed(feedLines))
        if (cut) out.write(EscPos.cut())
    }

    /** Sends [data] to the current target; [onDone] receives the byte count or the failure. */
    fun send(data: ByteArray, onDone: (Result<Int>) -> Unit) {
        val h = host.trim()
        val p = port.trim().toIntOrNull()
        if (h.isEmpty() || p == null || p !in 1..65535) {
            onDone(Result.failure(BadTargetException()))
            return
        }
        prefs.edit()
            .putString(KEY_HOST, h).putString(KEY_PORT, p.toString())
            .putString(KEY_CODEPAGE, codePage.name).putString(KEY_ESC_T, escTOverride)
            .putInt(KEY_IMG_WIDTH, imageWidth)
            .apply()
        sending = true
        viewModelScope.launch {
            val result = runCatching {
                PrinterTransport.send(protocol, h, p, data, lpdQueue)
                data.size
            }
            sending = false
            onDone(result)
        }
    }

    private companion object {
        const val KEY_RANGE = "range"
        const val KEY_PORTS = "ports"
        const val KEY_TIMEOUT = "timeout"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_CODEPAGE = "codepage"
        const val KEY_ESC_T = "esc_t"
        const val KEY_IMG_WIDTH = "img_width"
    }
}
