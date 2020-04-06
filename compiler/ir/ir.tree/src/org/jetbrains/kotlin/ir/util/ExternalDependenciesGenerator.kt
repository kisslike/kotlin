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

package org.jetbrains.kotlin.ir.util

import org.jetbrains.kotlin.builtins.functions.FunctionClassDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.descriptors.WrappedDeclarationDescriptor
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.resolve.descriptorUtil.parentsWithSelf
import org.jetbrains.kotlin.utils.addToStdlib.firstNotNullResult

class ExternalDependenciesGenerator(val symbolTable: SymbolTable, private val irProviders: List<IrProvider>) {
    fun generateUnboundSymbolsAsDependencies() {
        // There should be at most one DeclarationStubGenerator (none in closed world?)
        irProviders.singleOrNull { it is DeclarationStubGenerator }?.let {
            (it as DeclarationStubGenerator).unboundSymbolGeneration = true
        }
        irProviders.firstOrNull{ it is StubIrProvider }?.let {
            (it as StubIrProvider).declarationStubGenerator.unboundSymbolGeneration = true
        }
        /*
            Deserializing a reference may lead to new unbound references, so we loop until none are left.
         */
        lateinit var unbound: List<IrSymbol>
        var combinedHash: Int = -1
        do {
            val prevHash = combinedHash
            unbound = symbolTable.allUnbound
            combinedHash = if (unbound.isEmpty()) 0 else unbound.map { it.hashCode() }.reduce{ x,y -> x xor y }

            for (symbol in unbound) {
                // Symbol could get bound as a side effect of deserializing other symbols.
                if (!symbol.isBound) {
                    irProviders.getDeclaration(symbol)
                }
                //assert(symbol.isBound) { "$symbol unbound even after deserialization attempt" }
            }
        } while (/*unbound.isNotEmpty()*/ prevHash != combinedHash)

        irProviders.forEach { (it as? IrDeserializer)?.declareForwardDeclarations() }
    }
}

val SymbolTable.allUnbound: List<IrSymbol>
    get() {
        val r = mutableListOf<IrSymbol>()
        r.addAll(unboundClasses)
        r.addAll(unboundConstructors)
        r.addAll(unboundEnumEntries)
        r.addAll(unboundFields)
        r.addAll(unboundSimpleFunctions)
        r.addAll(unboundProperties)
        r.addAll(unboundTypeParameters)
        r.addAll(unboundTypeAliases)
        return r
    }

fun List<IrProvider>.getDeclaration(symbol: IrSymbol): IrDeclaration? =
    firstNotNullResult { provider ->
        provider.getDeclaration(symbol)
    } //?: error("Could not find declaration for unbound symbol $symbol")

// In most cases, IrProviders list consist of an optional deserializer and a DeclarationStubGenerator.
fun generateTypicalIrProviderList(
    moduleDescriptor: ModuleDescriptor,
    irBuiltins: IrBuiltIns,
    symbolTable: SymbolTable,
    deserializer: IrDeserializer? = null,
    extensions: StubGeneratorExtensions = StubGeneratorExtensions.EMPTY
): List<IrProvider> {
    val stubGenerator = DeclarationStubGenerator(
        moduleDescriptor, symbolTable, irBuiltins.languageVersionSettings, extensions
    )
    return listOfNotNull(deserializer, stubGenerator).also {
        stubGenerator.setIrProviders(it)
    }
}

abstract class StubIrProvider (
    val declarationStubGenerator: DeclarationStubGenerator,
) : IrProvider {

    abstract fun applicable(symbol: IrSymbol): Boolean
    override fun getDeclaration(symbol: IrSymbol): IrDeclaration? = when {
        symbol.isBound -> symbol.owner as IrDeclaration
        applicable(symbol) -> declarationStubGenerator.getDeclaration(symbol)
        else -> null
    }
}

class IrProviderForFunctionInterfaces(declarationStubGenerator: DeclarationStubGenerator) : StubIrProvider(declarationStubGenerator) {
    override fun applicable(symbol: IrSymbol): Boolean =
        symbol.descriptor is FunctionClassDescriptor ||
        symbol.descriptor !is WrappedDeclarationDescriptor<*> && symbol.descriptor.containingDeclaration is FunctionClassDescriptor
}

// In most cases, IrProviders list consist of an optional deserializer and a DeclarationStubGenerator.
fun generateTypicalIrProviderList2( // TODO: don't push me.
    moduleDescriptor: ModuleDescriptor,
    irBuiltins: IrBuiltIns,
    symbolTable: SymbolTable,
    deserializer: IrDeserializer? = null,
    extensions: StubGeneratorExtensions = StubGeneratorExtensions.EMPTY
): List<IrProvider> {
    val stubGenerator = DeclarationStubGenerator(
        moduleDescriptor, symbolTable, irBuiltins.languageVersionSettings, extensions
    )
    return listOfNotNull(deserializer, IrProviderForFunctionInterfaces(stubGenerator)).also {
        stubGenerator.setIrProviders(it)
    }
}