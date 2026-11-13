package io.github.severka_monitor.northwatch

import north.core.Chain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScanTest {
    private val main = "north1zju65aymm3nsz3uhpxcv0x7vrsw2x3pq4ehdne9a682j3fzd5s2vsenx7fx"

    @Test
    fun anAddressIsTakenFromACodeOnlyIfItIsValidForTheNetwork() {
        assertEquals(main, Scan.address(main, Chain.MAIN))
        assertEquals(main, Scan.address("  $main\n", Chain.MAIN))
        assertEquals(main, Scan.address("north:$main?amount=1.5&label=x", Chain.MAIN))
        assertEquals(main, Scan.address("truenorth:$main", Chain.MAIN))
        assertNull(Scan.address(main, Chain.REGTEST))
        assertNull(Scan.address("https://example.org/pay", Chain.MAIN))
        assertNull(Scan.address("", Chain.MAIN))
        assertNull(Scan.address(main.dropLast(1) + "y", Chain.MAIN))
        assertNull(Scan.address("north:${main.dropLast(1)}y", Chain.MAIN))
    }
}
