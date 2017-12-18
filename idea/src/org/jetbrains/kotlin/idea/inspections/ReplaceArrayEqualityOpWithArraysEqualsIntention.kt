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

package org.jetbrains.kotlin.idea.inspections

import com.intellij.codeInspection.*
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.intentions.resolvedToArrayType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.jetbrains.kotlin.psi.createExpressionByPattern
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall

class ReplaceArrayEqualityOpWithArraysEqualsInspection : AbstractKotlinInspection() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession) =
            object : KtVisitorVoid() {
                override fun visitBinaryExpression(expression: KtBinaryExpression) {
                    super.visitBinaryExpression(expression)

                    val operationToken = expression.operationToken
                    val operationNotation = when (operationToken) {
                        KtTokens.EQEQ -> "=="
                        KtTokens.EXCLEQ -> "!="
                        else -> return
                    }
                    val right = expression.right ?: return
                    val left = expression.left ?: return
                    val context = expression.analyze()
                    val rightResolvedCall = right.getResolvedCall(context) ?: return
                    if (!rightResolvedCall.resolvedToArrayType()) return
                    val leftResolvedCall = left.getResolvedCall(context) ?: return
                    if (leftResolvedCall.resolvedToArrayType()) {
                        holder.registerProblem(
                                expression,
                                "Dangerous array comparison",
                                ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                                ReplaceWithContentEqualsFix(operationNotation)
                        )
                    }
                }
            }

    private class ReplaceWithContentEqualsFix(val operationNotation: String) : LocalQuickFix {
        override fun getName() = "Replace '$operationNotation' with 'contentEquals'"

        override fun getFamilyName() = "Replace with 'contentEquals'"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val element = descriptor.psiElement as? KtBinaryExpression ?: return
            val right = element.right ?: return
            val left = element.left ?: return
            val factory = KtPsiFactory(project)
            val template = buildString {
                if (element.operationToken == KtTokens.EXCLEQ) append("!")
                append("$0.contentEquals($1)")
            }
            val expression = factory.createExpressionByPattern(template, left, right)
            element.replace(expression)
        }
    }
}
