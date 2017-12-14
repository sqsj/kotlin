/*
 * Copyright 2010-2016 JetBrains s.r.o.
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

package org.jetbrains.kotlin.resolve.scopes

import org.jetbrains.kotlin.descriptors.ClassifierDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ReceiverParameterDescriptor
import org.jetbrains.kotlin.incremental.components.LookupLocation
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.scopes.utils.takeSnapshot
import org.jetbrains.kotlin.util.collectionUtils.getFirstClassifierDiscriminateHeaders
import org.jetbrains.kotlin.util.collectionUtils.getFromAllScopes
import org.jetbrains.kotlin.utils.Printer

class LexicalChainedScope @JvmOverloads constructor(
        parent: LexicalScope,
        override val ownerDescriptor: DeclarationDescriptor,
        override val isOwnerDescriptorAccessibleByLabel: Boolean,
        override val implicitReceiver: ReceiverParameterDescriptor?,
        override val kind: LexicalScopeKind,
        private val memberScopes: List<MemberScope>,
        @Deprecated("This value is temporary hack for resolve -- don't use it!")
        val isStaticScope: Boolean = false,
        val isDeprecated: Boolean = false
) : LexicalScope {
    override val parent = parent.takeSnapshot()

    override fun getContributedDescriptors(kindFilter: DescriptorKindFilter, nameFilter: (Name) -> Boolean)
            = getFromAllScopes(memberScopes) { it.getContributedDescriptors() }

    override fun getContributedClassifier(name: Name, location: LookupLocation) = getFirstClassifierDiscriminateHeaders(memberScopes) { it.getContributedClassifier(name, location) }

    fun getContributedClassifierWithDeprecationStatus(name: Name, location: LookupLocation): Pair<ClassifierDescriptor, Boolean>? {
        // We have to carefully inspect member scopes, because it is possible that 'this'-scope is not deprecated, but some
        // of memberScopes actually is deprecated. Example:
        //
        // open class Base {
        //   companion object {
        //     class Foo
        //   }
        // }
        //
        // class Derived : Base() {
        //   ...
        // }
        //
        // Here, when building scopes for class Derived, we create LexicalChainedScope for members of Base. This scope itself isn't
        // deprecated, but among other scopes, it contains memberScope of nested classes from companion object -- which *is* deprecated
        // Therefore, if we get classifier from such scope, we have to inform that it is deprecated.
        //
        // Of course, if 'this'-scope is deprecated, then all classifiers from it are deprecated, no matter where they come from
        var resultingDeprecation: Boolean = isDeprecated

        val resultingClassifier = getFirstClassifierDiscriminateHeaders(memberScopes) { scope ->
            val classifier = scope.getContributedClassifier(name, location)
            if (classifier != null) resultingDeprecation = resultingDeprecation || ((scope as? ChainedMemberScope)?.isDeprecated ?: false)
            classifier
        }

        return resultingClassifier?.let { it to resultingDeprecation }
    }

    override fun getContributedVariables(name: Name, location: LookupLocation) = getFromAllScopes(memberScopes) { it.getContributedVariables(name, location) }

    override fun getContributedFunctions(name: Name, location: LookupLocation) = getFromAllScopes(memberScopes) { it.getContributedFunctions(name, location) }

    override fun toString(): String = kind.toString()

    override fun printStructure(p: Printer) {
        p.println(this::class.java.simpleName, ": ", kind, "; for descriptor: ", ownerDescriptor.name,
                  " with implicitReceiver: ", implicitReceiver?.value ?: "NONE", " {")
        p.pushIndent()

        for (scope in memberScopes) {
            scope.printScopeStructure(p)
        }

        p.print("parent = ")
        parent.printStructure(p.withholdIndentOnce())

        p.popIndent()
        p.println("}")
    }

}
