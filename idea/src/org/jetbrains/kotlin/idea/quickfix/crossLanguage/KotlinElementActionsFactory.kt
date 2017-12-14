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

package org.jetbrains.kotlin.idea.quickfix.crossLanguage

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.QuickFixFactory
import com.intellij.lang.jvm.*
import com.intellij.lang.jvm.actions.JvmElementActionsFactory
import com.intellij.lang.jvm.actions.MemberRequest
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.impl.source.tree.java.PsiReferenceExpressionImpl
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.asJava.unwrapped
import org.jetbrains.kotlin.idea.quickfix.AddModifierFix
import org.jetbrains.kotlin.idea.quickfix.RemoveModifierFix
import org.jetbrains.kotlin.idea.quickfix.createFromUsage.callableBuilder.ParameterInfo
import org.jetbrains.kotlin.idea.quickfix.createFromUsage.callableBuilder.ConstructorInfo
import org.jetbrains.kotlin.idea.quickfix.createFromUsage.callableBuilder.TypeInfo
import org.jetbrains.kotlin.idea.quickfix.createFromUsage.createCallable.CreateCallableFromUsageFix
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.uast.UElement

class KotlinElementActionsFactory : JvmElementActionsFactory() {
    companion object {
        private class FakeExpressionFromParameter(private val psiParam: PsiParameter) : PsiReferenceExpressionImpl() {
            override fun getText(): String = psiParam.name!!
            override fun getProject(): Project = psiParam.project
            override fun getParent(): PsiElement = psiParam.parent
            override fun getType(): PsiType? = psiParam.type
            override fun isValid(): Boolean = true
            override fun getContainingFile(): PsiFile = psiParam.containingFile
            override fun getReferenceName(): String? = psiParam.name
            override fun resolve(): PsiElement? = psiParam
        }

        val javaPsiModifiersMapping = mapOf(
                JvmModifier.PRIVATE to KtTokens.PRIVATE_KEYWORD,
                JvmModifier.PUBLIC to KtTokens.PUBLIC_KEYWORD,
                JvmModifier.PROTECTED to KtTokens.PUBLIC_KEYWORD,
                JvmModifier.ABSTRACT to KtTokens.ABSTRACT_KEYWORD
        )

        private inline fun <reified T : KtElement> JvmElement.toKtElement(): T? {
            val sourceElement = sourceElement
            return when {
                sourceElement is T -> sourceElement
                sourceElement is UElement -> sourceElement.psi?.unwrapped as? T
                else -> null
            }
        }

        private fun fakeParametersExpressions(parameters: List<JvmParameter>, project: Project): Array<PsiExpression>? =
                when {
                    parameters.isEmpty() -> emptyArray()
                    else -> JavaPsiFacade
                            .getElementFactory(project)
                            .createParameterList(
                                    parameters.map { it.name }.toTypedArray(),
                                    parameters.map { it.type as? PsiType ?: return null }.toTypedArray()
                            )
                            .parameters
                            .map(::FakeExpressionFromParameter)
                            .toTypedArray()
                }
    }

    override fun createChangeModifierActions(target: JvmModifiersOwner, request: MemberRequest.Modifier): List<IntentionAction> {
        val kModifierOwner = target.toKtElement<KtModifierListOwner>() ?: return emptyList()

        val modifier = request.modifier
        val shouldPresent = request.shouldPresent
        val (kToken, shouldPresentMapped) = if (JvmModifier.FINAL == modifier)
            KtTokens.OPEN_KEYWORD to !shouldPresent
        else
            javaPsiModifiersMapping[modifier] to shouldPresent

        if (kToken == null) return emptyList()
        val action = if (shouldPresentMapped)
            AddModifierFix.createIfApplicable(kModifierOwner, kToken)
        else
            RemoveModifierFix(kModifierOwner, kToken, false)
        return listOfNotNull(action)
    }

    override fun createAddConstructorActions(targetClass: JvmClass, request: MemberRequest.Constructor): List<IntentionAction> {
        val targetKtClass = targetClass.toKtElement<KtClass>() ?: return emptyList()

        if (request.typeParameters.isNotEmpty()) return emptyList()
        val ktModifiers = request.modifiers.map { javaPsiModifiersMapping[it] ?: return emptyList() }

        val project = targetKtClass.project

        val parameterInfos = request.parameters.withIndex().map { (index, param) ->
            ParameterInfo(TypeInfo.Empty)
        }
        val constructorInfo = ConstructorInfo(parameterInfos, targetKtClass, !targetKtClass.hasExplicitPrimaryConstructor())
        val addConstructorAction = CreateCallableFromUsageFix(targetKtClass, listOf(constructorInfo))

        val changePrimaryConstructorAction = run {
            val primaryConstructor = targetKtClass.primaryConstructor ?: return@run null
            val lightMethod = primaryConstructor.toLightMethods().firstOrNull() ?: return@run null
            val fakeParametersExpressions = fakeParametersExpressions(request.parameters, project) ?: return@run null
            QuickFixFactory.getInstance()
                    .createChangeMethodSignatureFromUsageFix(
                            lightMethod,
                            fakeParametersExpressions,
                            PsiSubstitutor.EMPTY,
                            targetKtClass,
                            false,
                            2
                    ).takeIf { it.isAvailable(project, null, targetKtClass.containingFile) }
        }

        return listOfNotNull(changePrimaryConstructorAction, addConstructorAction)
    }
}