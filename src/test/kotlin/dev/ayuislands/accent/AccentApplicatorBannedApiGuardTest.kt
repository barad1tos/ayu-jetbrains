package dev.ayuislands.accent

import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Handle
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.tree.AbstractInsnNode
import org.jetbrains.org.objectweb.asm.tree.ClassNode
import org.jetbrains.org.objectweb.asm.tree.FieldInsnNode
import org.jetbrains.org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.jetbrains.org.objectweb.asm.tree.LdcInsnNode
import org.jetbrains.org.objectweb.asm.tree.MethodInsnNode
import org.jetbrains.org.objectweb.asm.tree.TypeInsnNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Accent transactions refresh observed peers without re-entering the platform LookAndFeel lifecycle. */
class AccentApplicatorBannedApiGuardTest {
    private val references: Set<String> by lazy {
        collectReferences(
            listOf(
                "dev/ayuislands/accent/AccentApplicator",
                "dev/ayuislands/accent/ExternalChromeOwnership",
                "dev/ayuislands/accent/AccentApplicatorKt",
                "dev/ayuislands/accent/AccentApplyPlanRunner",
                "dev/ayuislands/studio/session/StudioAccentHost",
                "dev/ayuislands/studio/session/IdeAccentHost",
                "dev/ayuislands/studio/session/StudioAccentOperation",
                "dev/ayuislands/studio/session/StudioAccentParticipant",
            ),
        ) { name ->
            val resource =
                checkNotNull(javaClass.classLoader.getResourceAsStream("$name.class")) {
                    "Accent transaction class is missing: $name"
                }
            resource.use { it.readBytes() }
        }
    }

    @Test
    fun `accent transactions do not call SwingUtilities updateComponentTreeUI`() {
        assertFalse(
            SWING_REFRESH in references,
            "Accent transactions must not refresh Swing delegates: that can trigger SlowOperations failures. " +
                "Use ComponentTreeRefresher.notifyOnly or window.repaint() instead.",
        )
    }

    @Test
    fun `accent transactions do not call LafManager updateUI`() {
        assertFalse(
            LAF_REFRESH in references,
            "Accent transactions must not call LafManager.updateUI: it recurses through the LAF cycle.",
        )
    }

    @Test
    fun `accent transactions do not reference LafManagerListener`() {
        assertFalse(
            references.any { it == LAF_LISTENER || "L$LAF_LISTENER;" in it },
            "Accent transactions must not publish or subscribe to LafManagerListener.TOPIC: it recurses.",
        )
    }

    @Test
    fun `bytecode audit finds forbidden APIs inside owned nested classes`() {
        val rootName = "guard/AccentRoot"
        val nestedName = "$rootName\$Continuation"
        val root = ClassWriter(0)
        root.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, rootName, null, "java/lang/Object", null)
        root.visitInnerClass(nestedName, rootName, "Continuation", Opcodes.ACC_STATIC)
        root.visitEnd()
        val nested = ClassWriter(0)
        nested.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, nestedName, null, "java/lang/Object", null)
        val refresh = nested.visitMethod(Opcodes.ACC_STATIC, "refresh", "()V", null, null)
        refresh.visitCode()
        refresh.visitInsn(Opcodes.ACONST_NULL)
        refresh.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "javax/swing/SwingUtilities",
            "updateComponentTreeUI",
            "(Ljava/awt/Component;)V",
            false,
        )
        refresh.visitInsn(Opcodes.ACONST_NULL)
        refresh.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/intellij/ide/ui/LafManager", "updateUI", "()V", false)
        refresh.visitFieldInsn(Opcodes.GETSTATIC, LAF_LISTENER, "TOPIC", "Lcom/intellij/util/messages/Topic;")
        refresh.visitInsn(Opcodes.POP)
        refresh.visitInsn(Opcodes.RETURN)
        refresh.visitMaxs(1, 0)
        refresh.visitEnd()
        nested.visitEnd()
        val classes = mapOf(rootName to root.toByteArray(), nestedName to nested.toByteArray())
        val loaded = mutableListOf<String>()

        val actual =
            collectReferences(listOf(rootName)) { name ->
                loaded.add(name)
                classes.getValue(name)
            }

        assertEquals(listOf(rootName, nestedName), loaded)
        assertTrue(SWING_REFRESH in actual)
        assertTrue(LAF_REFRESH in actual)
        assertTrue(LAF_LISTENER in actual)
    }

    private fun collectReferences(
        roots: List<String>,
        readClass: (String) -> ByteArray,
    ): Set<String> {
        val pending = ArrayDeque(roots)
        val visited = mutableSetOf<String>()
        val result = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val className = pending.removeFirst()
            if (!visited.add(className)) continue
            val node = ClassNode()
            ClassReader(readClass(className)).accept(node, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            pending.addAll(node.innerClasses.map { it.name }.filter { it.startsWith("$className$") })
            result.addAll(node.interfaces)
            result.addAll(node.fields.map { it.desc })
            node.methods.forEach { method ->
                result.add(method.desc)
                method.instructions.forEach { result.addAll(instructionReferences(it)) }
            }
        }
        return result
    }

    private fun instructionReferences(instruction: AbstractInsnNode): List<String> =
        when (instruction) {
            is MethodInsnNode -> listOf(instruction.owner, "${instruction.owner}.${instruction.name}", instruction.desc)
            is FieldInsnNode -> listOf(instruction.owner, instruction.desc)
            is TypeInsnNode -> listOf(instruction.desc)
            is InvokeDynamicInsnNode ->
                listOf(instruction.desc) +
                    constantReferences(instruction.bsm) +
                    instruction.bsmArgs.flatMap(::constantReferences)
            is LdcInsnNode -> constantReferences(instruction.cst)
            else -> emptyList()
        }

    // ASM exposes constant-pool values as Object; strings are data, not API references.
    private fun constantReferences(value: Any): List<String> =
        when (value) {
            is Handle -> listOf(value.owner, "${value.owner}.${value.name}", value.desc)
            is Type -> listOf(value.descriptor)
            else -> emptyList()
        }

    private companion object {
        const val SWING_REFRESH = "javax/swing/SwingUtilities.updateComponentTreeUI"
        const val LAF_REFRESH = "com/intellij/ide/ui/LafManager.updateUI"
        const val LAF_LISTENER = "com/intellij/ide/ui/LafManagerListener"
    }
}
