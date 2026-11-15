package org.polarka.wallet

import north.core.Account
import north.core.Chain
import north.core.Words
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse

/**
 * 0.6.1. A payment is written down with what its coins held together ([Pending.inputSat]). Through a wallet
 * server that is the only way to tell what a waiting payment gives back: the server shows such a payment as
 * a loss and nothing more. The note has to come back from the wallet's file, also when the payment moves
 * to those that did not go through and back, and a file of an earlier version, which has no such note, has
 * to be read as before.
 */
class WhatAPaymentPutInTest {
    private val xpub = Account.fromWords(Words.generate(), Chain.MAIN).watchKey().xpub
    private fun id(n: Int) = "%064x".format(n)

    @Test
    fun theNoteComesBackFromTheWalletsFile() {
        val waiting = Pending(id(1), 500_000_000, 326, setOf(id(2) + ":0"), unsure = true, atMillis = 7, inputSat = 51_200_000_000)
        val gone = Dropped(id(3), 100, 200, setOf(id(4) + ":1"), atMillis = 9, inputSat = 12_345)
        val info = WalletInfo(Chain.MAIN, xpub, pending = listOf(waiting), dropped = listOf(gone))
        val read = WalletInfo.parse(info.toJson())
        assertEquals(info, read)
        assertEquals(51_200_000_000 to 12_345L, read.pending.single().inputSat to read.dropped.single().inputSat)
    }

    @Test
    fun aFileOfAnEarlierVersionHasNoNoteAndIsReadAsBefore() {
        val info = WalletInfo(
            Chain.MAIN, xpub, pending = listOf(Pending(id(1), 5, 6, setOf(id(2) + ":0"), inputSat = 77)),
            dropped = listOf(Dropped(id(3), 1, 2, setOf(id(4) + ":1"), inputSat = 88)),
        )
        val old = info.toJson().replace(",\"in\":77", "").replace(",\"in\":88", "")
        assertFalse("\"in\"" in old)
        val read = WalletInfo.parse(old)
        assertEquals(info.copy(pending = info.pending.map { it.copy(inputSat = 0) }, dropped = info.dropped.map { it.copy(inputSat = 0) }), read)
    }

    @Test
    fun aNoteThatCannotBeIsRefused() {
        val info = WalletInfo(Chain.MAIN, xpub, pending = listOf(Pending(id(1), 5, 6, setOf(id(2) + ":0"), inputSat = 77)))
        assertEquals(77L, WalletInfo.parse(info.toJson()).pending.single().inputSat)
        for (wrong in listOf("-1", "\"77\"", "1.5", "null", "200000000000000001", "[]", "true")) {
            assertFails(wrong) { WalletInfo.parse(info.toJson().replace("\"in\":77", "\"in\":$wrong")) }
        }
    }
}
