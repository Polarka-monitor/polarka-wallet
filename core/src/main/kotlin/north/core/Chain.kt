package north.core

/**
 * Network parameters of TrueNorth Core v30.5.4 (src/kernel/chainparams.cpp).
 * coinType is the SLIP-44 number in the key path; the prefixes are the version bytes of extended keys.
 */
enum class Chain(val hrp: String, val coinType: Long, val xprvPrefix: Int, val xpubPrefix: Int) {
    MAIN("north", 1867, 0x7e5c6544, 0x7e5d5a83),
    REGTEST("rnorth", 1, 0x04358390, 0x043587cb),
}
