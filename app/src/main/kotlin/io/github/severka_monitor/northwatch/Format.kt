package io.github.severka_monitor.northwatch

/** Amounts are kept in base units (1 NORTH = 100,000,000) and only turned into text here. */
object Format {
    private const val UNIT = 100_000_000L
    private const val THIN = '\u202f'                // a narrow space that does not break the line

    /** 1996800023484 becomes "19 968.00023484": always eight decimals, so that two amounts line up. */
    fun coins(sat: Long): String {
        require(sat >= 0) { "an amount is not negative" }
        val whole = (sat / UNIT).toString()
        val grouped = StringBuilder()
        for ((i, c) in whole.withIndex()) {
            if (i > 0 && (whole.length - i) % 3 == 0) grouped.append(THIN)
            grouped.append(c)
        }
        return grouped.toString() + "." + (sat % UNIT).toString().padStart(8, '0')
    }

    /** A long address in a shape the eye can compare: the first and the last characters. */
    fun shortAddress(address: String): String = if (address.length <= 24) address else address.take(12) + "…" + address.takeLast(8)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** "12.5" or "12,5" to base units; null if it is not a positive amount with at most eight decimals. */
    fun parseCoins(text: String): Long? {
        val t = text.filter { !it.isWhitespace() && !Character.isSpaceChar(it) }.replace(',', '.')
        val parts = t.split('.')
        if (parts.size > 2 || parts[0].isEmpty() || parts[0].length > 11 || !parts[0].all { it in '0'..'9' }) return null
        val fraction = parts.getOrElse(1) { "" }
        if (fraction.length > 8 || !fraction.all { it in '0'..'9' }) return null
        val sat = try {
            Math.addExact(Math.multiplyExact(parts[0].toLong(), UNIT), fraction.padEnd(8, '0').toLong())
        } catch (e: ArithmeticException) {
            return null                     // more than a signed 64-bit number of base units: not an amount of this coin
        }
        return if (sat > 0) sat else null
    }

    /** An amount with its sign, for the history. */
    fun signed(sat: Long): String = (if (sat < 0) "-" else "+") + coins(kotlin.math.abs(sat))

    fun decimal(value: Double, digits: Int): String = String.format(java.util.Locale.ROOT, "%." + digits + "f", value)

    fun hashrate(hashesPerSecond: Double): String = when {
        hashesPerSecond >= 1e9 -> decimal(hashesPerSecond / 1e9, 2) + " GH/s"
        hashesPerSecond >= 1e6 -> decimal(hashesPerSecond / 1e6, 2) + " MH/s"
        hashesPerSecond >= 1e3 -> decimal(hashesPerSecond / 1e3, 1) + " kH/s"
        else -> decimal(hashesPerSecond, 0) + " H/s"
    }

    private val clockFormat = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
    private val dateFormat = java.time.format.DateTimeFormatter.ofPattern("d. M. yyyy HH:mm")

    fun clock(millis: Long): String = clockFormat.withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(millis))

    fun dateTime(seconds: Long): String = dateFormat.withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochSecond(seconds))

    /** What an amount is worth at a price per coin typed in by the user; null if no usable price is set. */
    fun value(sat: Long, price: String, currency: String): String? = try {
        val plain = price.trim().replace(',', '.')
        // only a plain number is a price: this runs every time the overview is drawn and must never fail there
        if (sat < 0 || !Regex("[0-9]{1,12}(\\.[0-9]{1,8})?").matches(plain)) null else {
            val each = java.math.BigDecimal(plain)
            if (each.signum() <= 0) null else {
                val worth = java.math.BigDecimal(sat).movePointLeft(8).multiply(each).setScale(2, java.math.RoundingMode.HALF_UP)
                worth.toPlainString() + (if (currency.isBlank()) "" else " " + currency)
            }
        }
    } catch (e: Exception) {
        null
    }
}
