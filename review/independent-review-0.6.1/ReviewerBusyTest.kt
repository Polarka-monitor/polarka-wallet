package org.polarka.wallet

import north.core.Account
import north.core.Chain
import north.core.Words
import north.node.NodeError
import north.node.ServerClient
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Reviewer's test (0.6.1). Not part of the author's suite.
 *
 * On the real server the number of the picture (pending_revision) changes with every transaction that begins
 * to wait anywhere on the network. Here it changes between every two answers about history, as it would on a
 * network where a transaction arrives while each page is read. The history of the wallet is two pages long.
 *
 * changes-0.6.1.md says that the app "then tries three times and says that the server kept changing, or
 * shows its last look as one that nothing is judged from". This test shows the look failing outright with
 * the client's 409 (shown as Problem.Kind.NODE), with no snapshot at all, and after a single look: the
 * engine's three tries are never used, because ServerEngine.fetch retries only ServerClient.MOVED.
 */
class ReviewerBusyTest {
    @Test
    fun aHistoryOfTwoPagesOnAMovingPictureFailsTheWholeLook() {
        val node = ServerNode.start()
        val server = try { StandIn.start(node) } catch (e: Throwable) { node.close(); throw e }
        try {
            val wallet = Account.fromWords(Words.generate(), Chain.REGTEST)
            val info = WalletInfo(Chain.REGTEST, wallet.watchKey().xpub, lockOnOpen = false)
            node.mine(ServerClient.PAGE + 1, wallet.address(0, 0))             // 201 lines of history: two pages
            ServerRelay(server.url).use { relay ->
                val engine = ServerEngine(Chain.REGTEST, emptyList(), ServerClient(relay.url, maxWaitMs = 0))
                val steady = engine.fetch(info)                                  // the picture holds still: all is well
                assertEquals(ServerClient.PAGE + 1, steady.moves.size)
                val number = AtomicLong(1_000)
                relay.rewrite = { path, answer ->
                    if (path == "/v1/transactions") answer.replace(Regex("\"pending_revision\":\\d+"), "\"pending_revision\":${number.incrementAndGet()}") else answer
                }
                relay.paths.clear()
                val e = assertFailsWith<NodeError> { engine.fetch(info) }
                println("REVIEWER moving picture: ${e.httpStatus} ${e.code} '${e.message}', problem ${Problem.of(e)}, requests ${relay.paths}")
                assertEquals(409, e.httpStatus)
                assertEquals(Problem.Kind.NODE, Problem.of(e).kind)
                assertEquals(6, relay.paths.count { it == "/v1/transactions" }, "three readings of two pages, then the look gives up")
                assertEquals(1, relay.paths.count { it == "/v1/utxos" }, "one look only")
            }
        } finally {
            server.close()
            node.close()
        }
    }
}
