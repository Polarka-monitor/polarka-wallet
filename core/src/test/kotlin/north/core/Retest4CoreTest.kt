package north.core

import fr.acinq.bitcoin.MnemonicCode
import java.util.Collections
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** Fifth look of the independent reviewer (0.5.1). Expected to pass: Words.fault is null exactly when Words.valid is true. */
class Retest4CoreTest {
    @Test
    fun faultAndValidNeverDisagree() {
        val random = Random(20261114)
        val list = MnemonicCode.englishWordlist
        var cases = 0
        var valid = 0
        val disagreements = ArrayList<String>()
        fun check(words: List<String>, what: String) {
            cases++
            val ok = Words.valid(words)
            if (ok) valid++
            if (ok != (Words.fault(words) == null)) disagreements.add(what)
        }
        repeat(400) { round ->
            val good = if (round % 2 == 0) Words.generate() else MnemonicCode.toMnemonics(ByteArray(16).also { random.nextBytes(it) })
            check(good, "valid words")
            check(good.map { it.uppercase() }, "capitals")
            check(good.map { it.replaceFirstChar { c -> c.uppercase() } }, "a capital at the start of each word")
            check(good.toMutableList().also { it[random.nextInt(it.size)] = list[random.nextInt(list.size)] }, "one word replaced by another word of the list")
            check(good.toMutableList().also { Collections.swap(it, random.nextInt(it.size), random.nextInt(it.size)) }, "two words swapped")
            check(good.dropLast(1), "one word short")
            check(good + good[0], "one word more")
            check(good + good, "twice the words")
            check(good.toMutableList().also { it[3] = it[3] + "x" }, "a slip of the finger")
            check(good.toMutableList().also { it[3] = it[3] + "," }, "a comma")
            check(good.toMutableList().also { it[3] = "" }, "an empty word")
            check(good.toMutableList().also { it[3] = " " + it[3] }, "a space inside a word")
            check(listOf(good[0] + good[1]) + good.drop(2), "two words without the space between them")
            check(Words.split(good.joinToString("  \n\t ").uppercase()), "typed in capitals with other white space")
        }
        for (bytes in listOf(20, 24, 28)) check(MnemonicCode.toMnemonics(ByteArray(bytes).also { random.nextBytes(it) }), "valid words of another length")
        check(emptyList(), "nothing")
        check(listOf(""), "one empty word")
        println("RETEST4 sound: Words.fault is null exactly when Words.valid is true: $cases cases ($valid of them valid), disagreements ${disagreements.size}" +
            (if (disagreements.isEmpty()) "" else " (first: ${disagreements.first()})"))
        assertEquals(emptyList(), disagreements.distinct())
    }

    /** What the message of the app would name: the places are counted from one, and they are the places in what was typed. */
    @Test
    fun thePlacesAreThoseOfWhatWasTyped() {
        val good = Words.generate()
        val typed = Words.split("  " + (good.take(4) + "qqq" + good.drop(5)).joinToString("   ") + "\n")
        assertEquals(Words.Fault.NotOnList(listOf(5)), Words.fault(typed))
        val many = Words.split((1..30).joinToString(" ") { "x$it" })
        assertEquals(Words.Fault.NotOnList((1..30).toList()), Words.fault(many))
        // a wrong number of words is said only when every word is on the list; a wrong word is always said first
        assertEquals(Words.Fault.NotOnList(listOf(25)), Words.fault(good + "qqq"))
        assertEquals(Words.Fault.Count(25), Words.fault(good + good[0]))
    }
}
