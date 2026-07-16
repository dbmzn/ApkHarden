package com.apkharden.packager.core

data class ShellPayloadMetadata(
    val payloadId: String,
    val dexCount: Int,
    val originalApplication: String,
    val originalComponentFactory: String,
    val dexSizes: List<Int>,
    val dexSha256: List<String>,
) {
    fun encode(): ByteArray = buildString {
        require(dexSizes.size == dexCount && dexSha256.size == dexCount)
        append("formatVersion=2\n")
        append("payloadId=").append(property(payloadId)).append('\n')
        append("dexCount=").append(dexCount).append('\n')
        append("originalApplication=").append(property(originalApplication)).append('\n')
        append("originalComponentFactory=").append(property(originalComponentFactory)).append('\n')
        repeat(dexCount) { index ->
            append("dex.").append(index).append(".size=").append(dexSizes[index]).append('\n')
            append("dex.").append(index).append(".sha256=").append(dexSha256[index]).append('\n')
        }
    }.encodeToByteArray()

    private fun property(value: String): String = buildString {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '=' -> append("\\=")
                ':' -> append("\\:")
                else -> append(char)
            }
        }
    }
}
