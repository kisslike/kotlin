/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.jvm.serialization

import org.jetbrains.kotlin.backend.common.LoggingContext
import org.jetbrains.kotlin.backend.common.serialization.CurrentModuleDeserializer
import org.jetbrains.kotlin.backend.common.serialization.DeserializationStrategy
import org.jetbrains.kotlin.backend.common.serialization.IrModuleDeserializer
import org.jetbrains.kotlin.backend.common.serialization.KotlinIrLinker
import org.jetbrains.kotlin.backend.common.serialization.encodings.BinarySymbolData
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor
import org.jetbrains.kotlin.descriptors.konan.KlibModuleOrigin
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.impl.IrModuleFragmentImpl
import org.jetbrains.kotlin.ir.descriptors.*
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.util.DeclarationStubGenerator
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.ir.util.IrExtensionGenerator
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.library.IrLibrary
import org.jetbrains.kotlin.load.java.descriptors.*
import org.jetbrains.kotlin.name.Name

class JvmIrLinker(logger: LoggingContext, builtIns: IrBuiltIns, symbolTable: SymbolTable, private val stubGenerator: DeclarationStubGenerator) :
    KotlinIrLinker(logger, builtIns, symbolTable, emptyList()) {

    override val functionalInteraceFactory: IrAbstractFunctionFactory = IrFunctionFactory(builtIns, symbolTable)

    override fun isBuiltInModule(moduleDescriptor: ModuleDescriptor): Boolean =
        moduleDescriptor.name.asString() == "<built-ins module>"

    // TODO: implement special Java deserializer
    override fun createModuleDeserializer(moduleDescriptor: ModuleDescriptor, klib: IrLibrary?, strategy: DeserializationStrategy): IrModuleDeserializer {
        if (klib != null) {
            assert(moduleDescriptor.getCapability(KlibModuleOrigin.CAPABILITY) != null)
            return JvmModuleDeserializer(moduleDescriptor, klib, strategy)
        }

        return MetadataJVMModuleDeserializer(moduleDescriptor, emptyList())
    }

    private inner class JvmModuleDeserializer(moduleDescriptor: ModuleDescriptor, klib: IrLibrary, strategy: DeserializationStrategy) :
        KotlinIrLinker.BasicIrModuleDeserializer(moduleDescriptor, klib, strategy)

    private fun DeclarationDescriptor.isJavaDescriptor(): Boolean {
        if (this is PackageFragmentDescriptor) {
            return fqName.startsWith(Name.identifier("java"))
        }
        return this is JavaClassDescriptor || this is JavaCallableMemberDescriptor || (containingDeclaration?.isJavaDescriptor() == true)
    }

    override fun createCurrentModuleDeserializer(moduleFragment: IrModuleFragment, dependencies: Collection<IrModuleDeserializer>, extensions: Collection<IrExtensionGenerator>): IrModuleDeserializer =
        JvmCurrentModuleDeserializer(moduleFragment, dependencies, extensions)

    private inner class JvmCurrentModuleDeserializer(moduleFragment: IrModuleFragment, dependencies: Collection<IrModuleDeserializer>, extensions: Collection<IrExtensionGenerator>) :
        CurrentModuleDeserializer(moduleFragment, dependencies, symbolTable, extensions) {
        override fun declareIrSymbol(symbol: IrSymbol) {
            val descriptor = symbol.descriptor

            if (descriptor.isJavaDescriptor()) {
                // Wrap java declaration with lazy ir
                stubGenerator.generateMemberStub(descriptor)
                return
            }

            super.declareIrSymbol(symbol)
        }
    }

    private inner class MetadataJVMModuleDeserializer(moduleDescriptor: ModuleDescriptor, dependencies: List<IrModuleDeserializer>) :
        IrModuleDeserializer(moduleDescriptor) {
        override fun contains(idSig: IdSignature): Boolean = true

        private val classDescriptor = WrappedClassDescriptor()
        private val propertyDescriptor = WrappedPropertyDescriptor()
        private val functionDescriptor = WrappedSimpleFunctionDescriptor()
        private val constructorDescriptor = WrappedClassConstructorDescriptor()
        private val enumEntryDescriptor = WrappedEnumEntryDescriptor()
        private val typeAliasDescriptor = WrappedTypeAliasDescriptor()

        override fun deserializeIrSymbol(idSig: IdSignature, symbolKind: BinarySymbolData.SymbolKind): IrSymbol {
            val symbol = symbolTable.run {
                when (symbolKind) {
                    BinarySymbolData.SymbolKind.CLASS_SYMBOL -> referenceClassFromLinker(classDescriptor, idSig)
                    BinarySymbolData.SymbolKind.PROPERTY_SYMBOL -> referencePropertyFromLinker(propertyDescriptor, idSig)
                    BinarySymbolData.SymbolKind.FUNCTION_SYMBOL -> referenceSimpleFunctionFromLinker(functionDescriptor, idSig)
                    BinarySymbolData.SymbolKind.CONSTRUCTOR_SYMBOL -> referenceConstructorFromLinker(constructorDescriptor, idSig)
                    BinarySymbolData.SymbolKind.ENUM_ENTRY_SYMBOL -> referenceEnumEntryFromLinker(enumEntryDescriptor, idSig)
                    BinarySymbolData.SymbolKind.TYPEALIAS_SYMBOL -> referenceTypeAliasFromLinker(typeAliasDescriptor, idSig)
                    else -> error("Unexpected type $symbolKind for sig $idSig")
                }
            }

            stubGenerator.generateMemberStub(symbol.descriptor)

            return symbol
        }

        override fun declareIrSymbol(symbol: IrSymbol) {
            assert(symbol.isPublicApi)
            stubGenerator.generateMemberStub(symbol.descriptor)
        }

        override fun addModuleReachableTopLevel(idSig: IdSignature) {
            TODO("Not yet implemented")
        }

        override fun deserializeReachableDeclarations() {
            TODO("Not yet implemented")
        }

        override fun postProcess() {}

        override val moduleFragment: IrModuleFragment = IrModuleFragmentImpl(moduleDescriptor, builtIns, emptyList())
        override val moduleDependencies: Collection<IrModuleDeserializer> = dependencies

    }
}