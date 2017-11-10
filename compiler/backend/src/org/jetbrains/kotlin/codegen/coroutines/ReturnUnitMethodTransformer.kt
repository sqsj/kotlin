/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.codegen.coroutines

import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.codegen.inline.isReturnsUnitMarker
import org.jetbrains.kotlin.codegen.optimization.boxing.isUnitInstance
import org.jetbrains.kotlin.codegen.optimization.common.ControlFlowGraph
import org.jetbrains.kotlin.codegen.optimization.common.asSequence
import org.jetbrains.kotlin.codegen.optimization.common.removeAll
import org.jetbrains.kotlin.codegen.optimization.transformer.MethodTransformer
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.tree.*

/*
 * Replace POP with ARETURN iff
 * 1) It is immediately followed by { GETSTATIC Unit.INSTANCE, ARETURN } sequences
 * 2) It is poping Unit
 */
object ReturnUnitMethodTransformer : MethodTransformer() {
    override fun transform(internalClassName: String, methodNode: MethodNode) {
        val unitMarks = findReturnsUnitMarks(methodNode)
        if (unitMarks.isEmpty()) return

        val units = findReturnUnitSequences(methodNode)
        if (units.isEmpty()) {
            cleanUpReturnsUnitMarkers(methodNode, unitMarks)
            return
        }

        val pops = methodNode.instructions.asSequence().filter { it.opcode == Opcodes.POP }.toList()
        val popSuccessors = findImmediateSuccessors(methodNode, pops)
        val sourceInsns = findSourceInstructions(internalClassName, methodNode, pops)
        val safePops = filterOutUnsafes(popSuccessors, units, sourceInsns, pops)

        // Replace POP with ARETURN for tail call optimization
        safePops.forEach { methodNode.instructions.set(it, InsnNode(Opcodes.ARETURN)) }
        cleanUpReturnsUnitMarkers(methodNode, unitMarks)
    }

    // Return list of POPs, which can be safely replaced by ARETURNs
    private fun filterOutUnsafes(
            popSuccessors: Map<AbstractInsnNode, List<AbstractInsnNode>>,
            units: List<AbstractInsnNode>,
            sourceInsns: Map<AbstractInsnNode, Set<AbstractInsnNode>>,
            pops: List<AbstractInsnNode>
    ): List<AbstractInsnNode> {
        val unsafePops = hashSetOf<AbstractInsnNode>()
        for ((pop, successors) in popSuccessors) {
            // 1) It is immediately followed by { GETSTATIC Unit.INSTANCE, ARETURN } sequences
            if (successors.any { it !in units }) {
                unsafePops.add(pop)
            }
            // 2) It is poping Unit
            if (sourceInsns[pop]!!.any { !isSuspendingCallReturningUnit(it) }) {
                unsafePops.add(pop)
            }
        }

        return pops.filterNot { it in unsafePops }
    }

    // Find instructions with do something on stack, ignoring markers
    // Return map {pop => list of found instructions}
    private fun findImmediateSuccessors(methodNode: MethodNode, pops: List<AbstractInsnNode>): Map<AbstractInsnNode, List<AbstractInsnNode>> {
        val popSuccessors = hashMapOf<AbstractInsnNode, List<AbstractInsnNode>>()
        val cfg = ControlFlowGraph.build(methodNode)
        for (pop in pops) {
            val queue = arrayListOf<AbstractInsnNode>()
            queue.addAll(cfg.getSuccessorsIndices(pop).map { methodNode.instructions[it] })
            val successors = arrayListOf<AbstractInsnNode>()
            while (queue.isNotEmpty()) {
                val current = queue.pop()
                if (current in successors || isReturnsUnitMarker(current)) continue
                if (current is LineNumberNode || current is LabelNode || current is JumpInsnNode || current.opcode == Opcodes.NOP) {
                    queue.addAll(cfg.getSuccessorsIndices(current).map { methodNode.instructions[it] })
                }
                else {
                    successors.add(current)
                }
            }
            popSuccessors[pop] = successors
        }
        return popSuccessors
    }

    private fun isSuspendingCallReturningUnit(node: AbstractInsnNode): Boolean {
        if (node !is MethodInsnNode) return false
        val marker = node.next?.next ?: return false
        if (!isReturnsUnitMarker(marker)) return false
        return true
    }

    private fun findSourceInstructions(
            internalClassName: String,
            methodNode: MethodNode,
            pops: List<AbstractInsnNode>
    ): Map<AbstractInsnNode, Set<AbstractInsnNode>> {
        val frames = analyze(internalClassName, methodNode, IgnoringCopyOperationSourceInterpreter())
        val res = hashMapOf<AbstractInsnNode, Set<AbstractInsnNode>>()
        for (pop in pops) {
            val index = methodNode.instructions.indexOf(pop)
            val frame = frames[index]
            res[pop] = frame.getStack(0).insns
        }
        return res
    }

    // Find { GETSTATIC kotlin/Unit.INSTANCE, ARETURN } sequences
    // Result is list of GETSTATIC kotlin/Unit.INSTANCE instructions
    private fun findReturnUnitSequences(methodNode: MethodNode): List<AbstractInsnNode> {
        val res = arrayListOf<AbstractInsnNode>()

        var foundUnit = false
        for (insn in methodNode.instructions.asSequence()) {
            if (foundUnit) {
                if (insn.opcode == Opcodes.ARETURN) {
                    res.add(insn.previous)
                }
                foundUnit = false
            }
            else {
                if (insn.isUnitInstance()) {
                    foundUnit = true
                }
            }
        }
        return res
    }

    private fun findReturnsUnitMarks(methodNode: MethodNode): Set<AbstractInsnNode> {
        return methodNode.instructions.asSequence().filter { isReturnsUnitMarker(it) }.toHashSet()
    }

    private fun cleanUpReturnsUnitMarkers(methodNode: MethodNode, unitMarks: Set<AbstractInsnNode>) {
        unitMarks.forEach { methodNode.instructions.removeAll(listOf(it.previous, it)) }
    }
}

