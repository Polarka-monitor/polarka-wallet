package org.polarka.wallet

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    private fun plain(s: String) = s.replace('\u202f', ' ')

    @Test
    fun coins() {
        assertEquals("0.00000000", plain(Format.coins(0)))
        assertEquals("0.00000001", plain(Format.coins(1)))
        assertEquals("512.00000000", plain(Format.coins(51_200_000_000)))
        assertEquals("19 968.00023484", plain(Format.coins(1_996_800_023_484)))
        assertEquals("1 234 567.80000000", plain(Format.coins(123_456_780_000_000)))
        assertEquals("92 233 720 368.54775807", plain(Format.coins(Long.MAX_VALUE)))
    }

    @Test
    fun amountsAreReadExactly() {
        assertEquals(1_250_000_000L, Format.parseCoins("12.5"))
        assertEquals(1_250_000_000L, Format.parseCoins(" 12,5 "))
        assertEquals(1L, Format.parseCoins("0.00000001"))
        assertEquals(51_200_000_000L, Format.parseCoins("512"))
        assertEquals(100_000_000L, Format.parseCoins("1."))
        assertEquals(1_996_800_023_484L, Format.parseCoins(Format.coins(1_996_800_023_484L)))        // what the app shows can be typed back
        for (bad in listOf("", "0", "0.0", "-1", "1e5", "1.000000001", "1.2.3", "abc", ".5", "12 NORTH", "99999999999", "92233720369")) {
            assertEquals(null, Format.parseCoins(bad), bad)
        }
        assertEquals(9_223_372_036_800_000_000L, Format.parseCoins("92233720368"))
    }

    @Test
    fun shortAddress() {
        assertEquals("north1zju65a…vsenx7fx", Format.shortAddress("north1zju65aymm3nsz3uhpxcv0x7vrsw2x3pq4ehdne9a682j3fzd5s2vsenx7fx"))
        assertEquals("short", Format.shortAddress("short"))
    }
}
