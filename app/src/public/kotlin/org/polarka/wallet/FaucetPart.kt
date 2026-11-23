package org.polarka.wallet

import androidx.compose.runtime.Composable

/**
 * The public edition has no faucet. Nothing stands on the receive page in its place, and none of the faucet's code,
 * address or texts is in this package: they are in the source set of the personal edition alone.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun FaucetPart(address: String) {
}
