package com.apkharden.packager.core

data class ShellPayloadMetadata(
    val payloadId: String,
    val dexCount: Int,
    val originalApplication: String,
    val originalComponentFactory: String,
) {
    fun encode(): ByteArray = buildString {
        append("formatVersion=1\n")
        append("payloadId=").append(property(payloadId)).append('\n')
        append("dexCount=").append(dexCount).append('\n')
        append("originalApplication=").append(property(originalApplication)).append('\n')
        append("originalComponentFactory=").append(property(originalComponentFactory)).append('\n')
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
