package com.apkharden.runtime

class HardenStringTable(
    fragmentA: ByteArray,
    fragmentB: ByteArray,
    ivs: Array<ByteArray>,
    ciphertexts: Array<ByteArray>,
) {
    internal val fragmentA = fragmentA.copyOf()
    internal val fragmentB = fragmentB.copyOf()
    private val ivs = Array(ivs.size) { index -> ivs[index].copyOf() }
    private val ciphertexts = Array(ciphertexts.size) { index -> ciphertexts[index].copyOf() }

    val size: Int
        get() = ciphertexts.size

    init {
        require(this.fragmentA.size == KEY_FRAGMENT_SIZE) {
            "fragmentA must contain $KEY_FRAGMENT_SIZE bytes"
        }
        require(this.fragmentB.size == KEY_FRAGMENT_SIZE) {
            "fragmentB must contain $KEY_FRAGMENT_SIZE bytes"
        }
        require(this.ivs.size == this.ciphertexts.size) {
            "ivs and ciphertexts must have the same size"
        }
        this.ivs.forEachIndexed { index, iv ->
            require(iv.size == GCM_IV_BYTES) {
                "iv[$index] must contain $GCM_IV_BYTES bytes"
            }
        }
        this.ciphertexts.forEachIndexed { index, ciphertext ->
            require(ciphertext.size >= GCM_TAG_BYTES) {
                "ciphertext[$index] must include a GCM authentication tag"
            }
        }
    }

    internal fun iv(entryId: Int): ByteArray = ivs[entryId]

    internal fun ciphertext(entryId: Int): ByteArray = ciphertexts[entryId]

    private companion object {
        const val KEY_FRAGMENT_SIZE = 16
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BYTES = 16
    }
}
