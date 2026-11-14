package north.node

import kotlin.test.Test
import kotlin.test.assertEquals

/** Which transaction in the blocks that were read spends a given coin (0.4.2). */
class SpendersTest {
    private fun id(n: Int) = "%064x".format(n)

    @Test
    fun theSpenderOfACoinIsFoundInTheBlocks() {
        val pays = ChainTx(id(10), false, listOf(TxInput(id(1), 0), TxInput(id(2), 3)), listOf(TxOutput(0, 5, "51")))
        val reward = ChainTx(id(11), true, emptyList(), listOf(TxOutput(0, 512, "51")))
        val other = ChainTx(id(12), false, listOf(TxInput(id(3), 1)), emptyList())
        val blocks = listOf(ChainBlock(id(100), 7, 1700000000, listOf(reward, pays)), ChainBlock(id(101), 8, 1700000300, listOf(other)))
        val asked = setOf(id(1) + ":0", id(2) + ":3", id(3) + ":0", id(4) + ":0")
        assertEquals(mapOf(id(1) + ":0" to id(10), id(2) + ":3" to id(10)), History.spenders(blocks, asked))
        assertEquals(emptyMap(), History.spenders(blocks, emptySet()))
        assertEquals(mapOf(id(3) + ":1" to id(12)), History.spenders(blocks, setOf(id(3) + ":1")))
    }
}
