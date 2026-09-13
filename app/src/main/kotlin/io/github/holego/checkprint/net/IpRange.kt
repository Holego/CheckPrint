package io.github.holego.checkprint.net

/**
 * Parses the IP range text the user types on the scan screen.
 *
 * Accepted forms (several can be combined with commas):
 *   192.168.1.0/24          CIDR block (network and broadcast addresses are skipped)
 *   192.168.1.1-254         last octet range
 *   10.0.0.1-10.0.0.50      full address range
 *   192.168.1.42            single address
 */
object IpRange {
    const val MAX_HOSTS = 4096

    class RangeTooLargeException(val count: Long) : IllegalArgumentException("too many hosts: $count")

    fun expand(text: String): List<String> {
        val result = LinkedHashSet<String>()
        val parts = text.split(',', ';', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        require(parts.isNotEmpty()) { "empty range" }
        for (part in parts) {
            val (first, last) = when {
                part.contains('/') -> cidr(part)
                part.contains('-') -> dash(part)
                else -> parseIp(part).let { it to it }
            }
            require(last >= first) { part }
            val count = last - first + 1
            if (count + result.size > MAX_HOSTS) throw RangeTooLargeException(count + result.size)
            for (ip in first..last) result += format(ip)
        }
        return result.toList()
    }

    private fun cidr(s: String): Pair<Long, Long> {
        val (ipStr, prefixStr) = s.split('/', limit = 2)
        val prefix = prefixStr.trim().toIntOrNull()?.takeIf { it in 0..32 } ?: throw IllegalArgumentException(s)
        val ip = parseIp(ipStr.trim())
        val mask = maskOf(prefix)
        val network = ip and mask
        val broadcast = network or (mask.inv() and 0xFFFFFFFFL)
        return if (prefix >= 31) network to broadcast else (network + 1) to (broadcast - 1)
    }

    private fun dash(s: String): Pair<Long, Long> {
        val (a, b) = s.split('-', limit = 2).map { it.trim() }
        val first = parseIp(a)
        val last = if (b.contains('.')) {
            parseIp(b)
        } else {
            val octet = b.toIntOrNull()?.takeIf { it in 0..255 } ?: throw IllegalArgumentException(s)
            (first and 0xFFFFFF00L) or octet.toLong()
        }
        return first to last
    }

    fun parseIp(s: String): Long {
        val parts = s.split('.')
        require(parts.size == 4) { s }
        var value = 0L
        for (p in parts) {
            val octet = p.toIntOrNull()?.takeIf { it in 0..255 } ?: throw IllegalArgumentException(s)
            value = (value shl 8) or octet.toLong()
        }
        return value
    }

    fun format(ip: Long): String =
        "${(ip shr 24) and 255}.${(ip shr 16) and 255}.${(ip shr 8) and 255}.${ip and 255}"

    private fun maskOf(prefix: Int): Long =
        if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL

    /** "192.168.1.37" + 24 -> "192.168.1.0/24" */
    fun cidrOf(ip: String, prefix: Int): String {
        val p = prefix.coerceIn(0, 32)
        return "${format(parseIp(ip) and maskOf(p))}/$p"
    }
}
