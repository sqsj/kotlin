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

package org.jetbrains.kotlin.idea.quickfix.createFromUsage

import com.intellij.codeInsight.ExpectedTypeInfo
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.asJava.classes.KtLightClassForFacade
import org.jetbrains.kotlin.asJava.classes.KtLightClassForSourceDeclaration
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.SourceElement
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.descriptors.impl.ClassDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.MutablePackageFragmentDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.getResolutionFacade
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.load.java.components.TypeUsage
import org.jetbrains.kotlin.load.java.lazy.JavaResolverComponents
import org.jetbrains.kotlin.load.java.lazy.LazyJavaResolverContext
import org.jetbrains.kotlin.load.java.lazy.TypeParameterResolver
import org.jetbrains.kotlin.load.java.lazy.child
import org.jetbrains.kotlin.load.java.lazy.descriptors.LazyJavaTypeParameterDescriptor
import org.jetbrains.kotlin.load.java.lazy.types.JavaTypeAttributes
import org.jetbrains.kotlin.load.java.lazy.types.JavaTypeResolver
import org.jetbrains.kotlin.load.java.structure.JavaTypeParameter
import org.jetbrains.kotlin.load.java.structure.impl.JavaTypeImpl
import org.jetbrains.kotlin.load.java.structure.impl.JavaTypeParameterImpl
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.types.KotlinType

private fun PsiType.collectTypeParameters(): List<PsiTypeParameter> {
    val results = ArrayList<PsiTypeParameter>()
    accept(
            object : PsiTypeVisitor<Unit>() {
                override fun visitArrayType(arrayType: PsiArrayType) {
                    arrayType.componentType.accept(this)
                }

                override fun visitClassType(classType: PsiClassType) {
                    (classType.resolve() as? PsiTypeParameter)?.let { results += it }
                    classType.parameters.forEach { it.accept(this) }
                }

                override fun visitWildcardType(wildcardType: PsiWildcardType) {
                    wildcardType.bound?.accept(this)
                }
            }
    )
    return results
}

private fun PsiType.resolveToKotlinType(resolutionFacade: ResolutionFacade): KotlinType? {
    val typeParameters = collectTypeParameters()
    val components = resolutionFacade.getFrontendService(JavaResolverComponents::class.java)
    val rootContext = LazyJavaResolverContext(components, TypeParameterResolver.EMPTY) { null }
    val dummyPackageDescriptor = MutablePackageFragmentDescriptor(resolutionFacade.moduleDescriptor, FqName("dummy"))
    val dummyClassDescriptor = ClassDescriptorImpl(
            dummyPackageDescriptor,
            Name.identifier("Dummy"),
            Modality.FINAL,
            ClassKind.CLASS,
            emptyList(),
            SourceElement.NO_SOURCE,
            false
    )
    val typeParameterResolver = object : TypeParameterResolver {
        override fun resolveTypeParameter(javaTypeParameter: JavaTypeParameter): TypeParameterDescriptor? {
            val psiTypeParameter = (javaTypeParameter as JavaTypeParameterImpl).psi
            val index = typeParameters.indexOf(psiTypeParameter)
            if (index < 0) return null
            return LazyJavaTypeParameterDescriptor(rootContext.child(this), javaTypeParameter, index, dummyClassDescriptor)
        }
    }
    val typeResolver = JavaTypeResolver(rootContext, typeParameterResolver)
    val attributes = JavaTypeAttributes(TypeUsage.COMMON)
    return typeResolver.transformJavaType(JavaTypeImpl.create(this), attributes)
}

private fun CreateFromUsage.TypeInfo.suggestTypes(contextElement: KtElement): List<KotlinType> {
    val psiManager = contextElement.manager
    val project = contextElement.project
    val scope = contextElement.useScope as? GlobalSearchScope ?: return emptyList()
    val expectedTypeInfos = typeConstraints.map { it as? ExpectedTypeInfo ?: return emptyList() }.toTypedArray()
    val psiTypes = ExpectedTypesProvider.processExpectedTypes(
            expectedTypeInfos,
            GuessTypeParameters.MyTypeVisitor(psiManager, scope),
            project
    )
    val resolutionFacade = contextElement.getResolutionFacade()
    return psiTypes.mapNotNull { it.resolveToKotlinType(resolutionFacade) }
}

internal fun PsiClass.getTargetContainer(): KtElement? {
    val targetLightClass = this as? KtLightClass ?: return null
    return when (targetLightClass) {
        is KtLightClassForSourceDeclaration -> targetLightClass.kotlinOrigin
        is KtLightClassForFacade -> targetLightClass.files.firstOrNull()
        else -> null
    }
}

internal fun CreateFromUsage.FieldInfo.createGenerateFieldFromUsageActions(): List<IntentionAction> {
    val targetContainer = targetClass.getTargetContainer() ?: return emptyList()
    val suggestedTypes = returnType.suggestTypes(targetContainer)
            .ifEmpty { listOf(targetContainer.getResolutionFacade().moduleDescriptor.builtIns.nullableAnyType) }
    val propertyInfo = PropertyInfo(
            name,
            TypeInfo.Empty,
            TypeInfo.ByExplicitCandidateTypes(suggestedTypes),
            !modifiers.contains(PsiModifier.FINAL),
            listOf(targetContainer),
            isForCompanion = modifiers.contains(PsiModifier.STATIC),
            isFromJava = true
    )
    return listOf(CreateCallableFromUsageFix(targetContainer, listOf(propertyInfo)))
}

internal fun CreateFromUsage.MethodInfo.createGenerateMethodFromUsageActions(): List<IntentionAction> {
    val targetContainer = targetClass.getTargetContainer() ?: return emptyList()
    val nullableAnyType = targetContainer.getResolutionFacade().moduleDescriptor.builtIns.nullableAnyType
    val suggestedReturnTypes = returnType.suggestTypes(targetContainer).ifEmpty { listOf(nullableAnyType) }
    val parameterInfos = parameters.map {
        val suggestedTypes = it.typeInfo.suggestTypes(targetContainer).ifEmpty { listOf(nullableAnyType) }
        ParameterInfo(TypeInfo.ByExplicitCandidateTypes(suggestedTypes), it.suggestedNames)
    }
    val functionInfo = FunctionInfo(
            name,
            TypeInfo.Empty,
            TypeInfo.ByExplicitCandidateTypes(suggestedReturnTypes),
            listOf(targetContainer),
            parameterInfos,
            isAbstract = modifiers.contains(PsiModifier.ABSTRACT),
            isForCompanion = modifiers.contains(PsiModifier.STATIC),
            isFromJava = true
    )
    return listOf(CreateCallableFromUsageFix(targetContainer, listOf(functionInfo)))
}

internal fun CreateFromUsage.ConstructorInfo.createGenerateConstructorFromUsageActions(): List<IntentionAction> {
    val targetKtClass = targetClass.getTargetContainer() as? KtClass ?: return emptyList()
    val nullableAnyType = targetKtClass.getResolutionFacade().moduleDescriptor.builtIns.nullableAnyType
    val parameterInfos = parameters.map {
        val suggestedTypes = it.typeInfo.suggestTypes(targetKtClass).ifEmpty { listOf(nullableAnyType) }
        ParameterInfo(TypeInfo.ByExplicitCandidateTypes(suggestedTypes), it.suggestedNames)
    }
    val constructorInfo = ConstructorInfo(
            parameterInfos,
            targetKtClass,
            isPrimary = !targetKtClass.hasExplicitPrimaryConstructor(),
            isFromJava = true
    )
    return listOf(CreateCallableFromUsageFix(targetKtClass, listOf(constructorInfo)))
}