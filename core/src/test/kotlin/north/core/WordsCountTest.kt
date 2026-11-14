package north.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 0.6: new words are 24 unless the owner asks for 12, and no other number is made. */
class WordsCountTest {
    @Test
    fun newWordsAreTwentyFourOrTwelve() {
        assertEquals(24, Words.generate().size)
        assertEquals(24, Words.generate(24).size)
        val twelve = Words.generate(12)
        assertEquals(12, twelve.size)
        assertTrue(Words.valid(twelve) && Words.valid(Words.generate()))
        assertNotEquals(twelve, Words.generate(12))
        // twelve words lead to a wallet like any other
        assertTrue(Account.fromWords(twelve, Chain.MAIN).address(0, 0).startsWith("north1z"))
        for (count in listOf(0, 11, 13, 15, 18, 21, 23, 25)) assertFailsWith<IllegalArgumentException> { Words.generate(count) }
    }
}
