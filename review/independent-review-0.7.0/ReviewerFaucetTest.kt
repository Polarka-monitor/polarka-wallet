package org.polarka.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reviewer's tests of Faucet (0.7.0), not part of the author's suite. Each one shows a behaviour, it does not
 * judge it: the report says what is wrong with it. Nothing here goes to a real network: the "faucet" is a socket
 * on 127.0.0.1 in this test.
 */
class ReviewerFaucetTest {
    /** A faucet that sends its answer a byte a second: one question of Faucet.ask lasts as long as the faucet likes. */
    @Test
    fun aFaucetThatTricklesIsWaitedForLongerThanAnyOfItsLimits() {
        val body = "{" + " ".repeat(78) + "\"error\":\"LATER\"}"          // 95 bytes, valid JSON
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val worker = thread(isDaemon = true) {
            server.accept().use { s ->
                val input = s.getInputStream()
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) { val b = input.read(); if (b < 0) break; head.append(b.toChar()) }
                val length = Regex("(?i)content-length: *(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                repeat(length) { input.read() }
                val out = s.getOutputStream()
                out.write(("HTTP/1.1 429 Too Many Requests\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray())
                out.flush()
                for (c in body) { out.write(c.code); out.flush(); Thread.sleep(1000) }
            }
        }
        val started = System.currentTimeMillis()
        val reason = try { Faucet.ask("north1zexample", "http://127.0.0.1:${server.localPort}"); "no refusal" } catch (e: Faucet.Refused) { e.reason }
        val took = System.currentTimeMillis() - started
        println("REVIEWER faucet trickle: one question took $took ms and ended as $reason (connect 15 s, read 30 s)")
        server.close()
        worker.join(5000)
        assertEquals("LATER", reason)
        assertTrue(took > 90_000, "the question took $took ms")
    }

    /** The work goes on after the coroutine that started it was cancelled, as when the owner leaves the page. */
    @Test
    fun theWorkIsNotStoppedByCancellingItsCoroutine() {
        var finished = false
        var tries = 0L
        val took = System.currentTimeMillis().let { t0 ->
            runBlocking {
                val job = launch(Dispatchers.Default) {
                    withContext(Dispatchers.Default) {
                        val answer = Faucet.solve("reviewer.cancel", 24, until = Long.MAX_VALUE / 1000)
                        tries = (answer?.toLong(16) ?: -1) + 1
                        finished = true
                    }
                }
                delay(50)
                job.cancel()
                job.join()
            }
            System.currentTimeMillis() - t0
        }
        println("REVIEWER cancel: the job was cancelled after 50 ms; the work ended anyway after $took ms and $tries tries")
        assertTrue(finished, "the work did not run to its end")
    }

    /** How fast Faucet.solve is on this computer (a desktop JVM, not a phone), and so what 20 and 24 bits cost. */
    @Test
    fun howFastTheWorkIs() {
        var tries = 0L
        val t0 = System.nanoTime()
        for (n in 0 until 6) {
            val answer = Faucet.solve("reviewer.rate.$n", 20, until = Long.MAX_VALUE / 1000)!!
            tries += answer.toLong(16) + 1
        }
        val seconds = (System.nanoTime() - t0) / 1e9
        val rate = tries / seconds
        println("REVIEWER rate: $tries tries in %.2f s, %.0f hashes a second; 2^24 expected tries would take %.1f s, 2^28 (the most) %.0f s"
            .format(seconds, rate, (1 shl 24) / rate, (1L shl 28) / rate))
        assertTrue(rate > 0)
    }
}
