package org.polarka.wallet

import org.junit.Test
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The faucet's side in the phone (personal edition), after the review of 0.7.0. L1: a question to the faucet has a
 * time limit as a whole, the work has one as well and can be stopped. L5: the work is the one faucet.py checks,
 * shown with numbers that faucet.py itself worked out, and the questions are asked of a stand-in that answers as
 * faucet.py does and of one that does not keep to the rules.
 */
class FaucetTest {
    private val address = "north1zju65aymm3nsz3uhpxcv0x7vrsw2x3pq4ehdne9a682j3fzd5s2vsenx7fx"

    private fun refused(block: () -> Unit): Faucet.Refused = assertFailsWith<Faucet.Refused> { block() }

    /**
     * Challenges of faucet.py's form and the answer that faucet.py's own `solve` finds for each: worked out with
     * polarka-agent 0.2.4 (505a3de) on 23 November 2026, `faucet.solve(challenge, bits)`, each checked there with
     * `faucet.work_done`. The phone finds the same answers: it tries the numbers in the same order and counts the
     * zero bits the same way.
     */
    @Test
    fun theWorkIsTheOneFaucetPyChecks() {
        val vectors = listOf(
            Triple("0.1795400772.e5c15b629362da5c.28f004817e6ddce7a7ace732f6fadfa7", 0, "0"),
            Triple("1.1795400177.8f7b7676d015b0dc.609e467ada679f818a119b9e16bc1732", 1, "2"),
            Triple("7.1795400397.2f960063bc67ba94.12e74f7bfab36728a78beb7e0787c0de", 7, "109"),
            Triple("8.1795400103.a712755f6cace4d5.ac8efe121c5989f1ba2dde83458bfed0", 8, "1ba"),
            Triple("12.1795400823.482c590e0d9782bf.53a12945a93258ef7777682c5796a670", 12, "3ad"),
            Triple("16.1795400046.0f8a7abff2e91ac7.789c1af40ce8fcc945ac16b9c20dbc21", 16, "27c3"),
            Triple("18.1795400842.c1cc0affdd2ee150.3226db943c3ae353fb14628e90b401a6", 18, "56d0"),
        )
        for ((challenge, bits, answer) in vectors) {
            assertEquals(answer, Faucet.solve(challenge, bits, until = Long.MAX_VALUE / 1000), challenge)
        }
        // zero bits of a digest, as faucet.py's leading_zero_bits counts them (worked out there for these digests)
        val digests = mapOf("80" + "00".repeat(31) to 0, "01" + "ff".repeat(31) to 7, "00ff" + "00".repeat(30) to 8, "000f" + "11".repeat(30) to 12,
            "0000" + "80".repeat(30) to 16, "00".repeat(32) to 256, "007f" + "ff".repeat(30) to 9)
        for ((hex, bits) in digests) {
            assertEquals(bits, Faucet.leadingZeroBits(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()), hex)
        }
    }

    @Test
    fun aClaimIsMadeWithWorkThatTheStandInChecksByItself() {
        FaucetStandIn(bits = 14).use { faucet ->
            assertEquals(faucet.txid, Faucet.ask(address, faucet.url))
            assertEquals(listOf(address), faucet.paid.toList())
            // a second time the faucet says no in its own word, and the phone says that it is the faucet's
            val e = refused { Faucet.ask(address, faucet.url) }
            assertEquals("ALREADY", e.reason)
            assertTrue(e.byFaucet)
            assertEquals(1, faucet.claims)
        }
    }

    @Test
    fun ofWhatAFaucetSendsOnlyItsWordsFromTheListAreShown() {
        FaucetStandIn().use { faucet ->
            val cases = mapOf(
                """{"error":"NOT_NOW","message":"the payment was made but its outcome is not known yet"}""" to ("NOT_NOW" to true),
                """{"error":"Visit example.org to claim your coins","message":"x"}""" to ("OTHER" to false),
                """{"error":{"word":"ALREADY"}}""" to ("OTHER" to false),
                """{"challenge":"x","bits":"8","expires":1}""" to ("OTHER" to false),          // a number written as a text
                """{"challenge":"","bits":8,"expires":9999999999}""" to ("OTHER" to false),
                """{"challenge":"${"a".repeat(201)}","bits":8,"expires":9999999999}""" to ("OTHER" to false),
                """not json at all""" to ("OTHER" to false),
                """{"challenge":"x","bits":25,"expires":9999999999}""" to ("TOO_MUCH" to false),
                """{"challenge":"x","bits":-1,"expires":9999999999}""" to ("TOO_MUCH" to false),
                """{"challenge":"x","bits":8,"expires":1}""" to ("RAN_OUT" to false),
            )
            for ((body, expected) in cases) {
                // a refusal comes with a failure status; a challenge, however odd, with success (since the ninth look)
                val status = if ("\"error\"" in body) 400 else 200
                faucet.misbehave = { if (it == "/v1/challenge") FaucetStandIn.Raw(FaucetStandIn.http(status, body)) else null }
                val e = refused { Faucet.ask(address, faucet.url) }
                assertEquals(expected, e.reason to e.byFaucet, body)
            }
            // ninth look, L-2: a failure status without one of the faucet's words is no answer, even with a challenge in it
            for (status in listOf(400, 500, 502)) {
                faucet.misbehave = { if (it == "/v1/challenge") FaucetStandIn.Raw(FaucetStandIn.http(status, """{"challenge":"x","bits":8,"expires":9999999999}""")) else null }
                assertEquals("OTHER" to false, refused { Faucet.ask(address, faucet.url) }.let { it.reason to it.byFaucet }, "HTTP $status")
            }
            // a claim whose answer names no transaction, or not one of 64 hex characters
            for (body in listOf("""{"asked":"x"}""", """{"transaction_id":"${"A".repeat(64)}"}""", """{"transaction_id":"12"}""")) {
                faucet.misbehave = { if (it == "/v1/claim") FaucetStandIn.Raw(FaucetStandIn.http(200, body)) else null }
                assertEquals("OTHER", refused { Faucet.ask(address, faucet.url) }.reason, body)
            }
            assertEquals(0, faucet.claims, "the stand-in took a claim it should not have seen")
            assertTrue(faucet.paid.isEmpty())
        }
    }

    @Test
    fun anAnswerTooLongOrARedirectIsNotFollowed() {
        FaucetStandIn().use { other ->
            FaucetStandIn().use { faucet ->
                faucet.misbehave = { FaucetStandIn.Raw(FaucetStandIn.http(200, "{\"pad\":\"" + "x".repeat(70_000) + "\"}")) }
                assertEquals("OTHER", refused { Faucet.ask(address, faucet.url) }.reason)
                faucet.misbehave = { FaucetStandIn.Raw(FaucetStandIn.http(307, "", "Location: ${other.url}/v1/challenge\r\n")) }
                assertEquals("OTHER", refused { Faucet.ask(address, faucet.url) }.reason)
                assertEquals(0, other.challenges, "the phone followed a redirect to another faucet")
            }
        }
    }

    @Test
    fun anAnswerThatTricklesInIsNotWaitedForWithoutEnd() {
        FaucetStandIn().use { faucet ->
            // the head at once, then a byte every 300 ms: each read is quick, the question as a whole is not
            val body = """{"challenge":"x","bits":8,"expires":9999999999,"pad":"${"x".repeat(200)}"}"""
            val raw = FaucetStandIn.http(200, body)
            faucet.misbehave = { FaucetStandIn.Raw(raw, pauseMs = 300, fastBytes = raw.size - body.length) }
            var started = System.nanoTime()
            assertEquals("SLOW", refused { Faucet.ask(address, faucet.url, withinMs = 2_000) }.reason)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000, "the trickle held the question too long")
            // and nothing at all after the connection: not even the head comes
            faucet.misbehave = { FaucetStandIn.Raw(raw, holdMs = 60_000) }
            started = System.nanoTime()
            assertEquals("SLOW", refused { Faucet.ask(address, faucet.url, withinMs = 2_000) }.reason)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000, "a silent faucet held the question too long")
        }
    }

    @Test
    fun theOwnerCanStopItWhileItWaitsAndWhileItWorks() {
        FaucetStandIn().use { faucet ->
            faucet.misbehave = { FaucetStandIn.Raw(FaucetStandIn.http(200, "{}"), holdMs = 60_000) }
            val asking = Faucet.Asking()
            thread { Thread.sleep(300); asking.stop() }
            val started = System.nanoTime()
            assertEquals("STOPPED", refused { Faucet.ask(address, faucet.url, asking) }.reason)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000, "stopping did not end the wait")
        }
        // 40 bits are never found in time; the work ends at its next look once it is told to stop
        val stopAt = System.nanoTime() + 50_000_000
        val started = System.nanoTime()
        assertNull(Faucet.solve("x", 40, until = Long.MAX_VALUE / 1000, stop = { System.nanoTime() > stopAt }))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000, "the work went on after it was told to stop")
    }

    @Test
    fun theWorkLastsNoLongerThanAChallengeOfFaucetPyLives() {
        FaucetStandIn(bits = 24).use { faucet ->
            faucet.expiresIn = 10L * 24 * 3600                // a faucet that says its challenge lives ten days
            var calls = 0
            val start = System.currentTimeMillis()
            // the phone's clock: as now while the challenge is read, then eleven minutes later
            val clock = { if (calls++ < 2) start else start + 660_000 }
            assertEquals("RAN_OUT", refused { Faucet.ask(address, faucet.url, now = clock) }.reason)
            assertEquals(0, faucet.claims)
        }
    }
}
