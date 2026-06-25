package com.apkharden.packager.dex

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.apkharden.packager.scanner.model.Located
import java.io.BufferedInputStream

/**
 * dexlib2 封装。解析一组 (entryName, bytes)，对外只暴露两张「表」：
 *  - typeDescriptors(): 每个 dex 里【定义】的类型（"Lpkg/Cls;"），用于判定 SDK 是否被打进包。
 *  - methodRefs(): 指令里【引用】的方法（"Lcls;->name"，不含参数签名），用于判定敏感 API 是否被调用。
 * 检测器只面对这两张表，不直接依赖 dexlib2 类型。
 */
class DexIndex(private val dexes: List<Pair<String, ByteArray>>) {

    private fun parse(bytes: ByteArray): DexBackedDexFile =
        DexBackedDexFile.fromInputStream(Opcodes.getDefault(), BufferedInputStream(bytes.inputStream()))

    fun typeDescriptors(): Sequence<Located<String>> = sequence {
        for ((name, bytes) in dexes) {
            val dex = runCatching { parse(bytes) }.getOrNull() ?: continue
            for (cls in dex.classes) yield(Located(cls.type, name))
        }
    }

    fun methodRefs(): Sequence<Located<String>> = sequence {
        for ((name, bytes) in dexes) {
            val dex = runCatching { parse(bytes) }.getOrNull() ?: continue
            for (cls in dex.classes) {
                for (method in cls.methods) {
                    val impl = method.implementation ?: continue
                    for (insn in impl.instructions) {
                        val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                        yield(Located("${ref.definingClass}->${ref.name}", name))
                    }
                }
            }
        }
    }
}
