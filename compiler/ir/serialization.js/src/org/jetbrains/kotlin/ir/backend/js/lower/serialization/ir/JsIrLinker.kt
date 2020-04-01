/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower.serialization.ir

import org.jetbrains.kotlin.backend.common.LoggingContext
import org.jetbrains.kotlin.backend.common.serialization.DeserializationStrategy
import org.jetbrains.kotlin.backend.common.serialization.IrModuleDeserializer
import org.jetbrains.kotlin.backend.common.serialization.KotlinIrLinker
import org.jetbrains.kotlin.backend.common.serialization.encodings.BinarySymbolData
import org.jetbrains.kotlin.descriptors.DeserializedDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.descriptors.IrAbstractFunctionFactory
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.descriptors.IrFunctionFactory
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.ir.util.IrExtensionGenerator
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.library.IrLibrary
import org.jetbrains.kotlin.library.SerializedIrFile

class JsIrLinker(logger: LoggingContext, builtIns: IrBuiltIns, symbolTable: SymbolTable, private val icData: Collection<SerializedIrFile>? = null) :
    KotlinIrLinker(logger, builtIns, symbolTable, emptyList()) {

    override val functionalInteraceFactory: IrAbstractFunctionFactory = IrFunctionFactory(builtIns, symbolTable)

    override fun isBuiltInModule(moduleDescriptor: ModuleDescriptor): Boolean =
        moduleDescriptor === moduleDescriptor.builtIns.builtInsModule

    override fun createModuleDeserializer(moduleDescriptor: ModuleDescriptor, klib: IrLibrary?, strategy: DeserializationStrategy): IrModuleDeserializer =
        JsModuleDeserializer(moduleDescriptor, klib ?: error("Expecting kotlin library"), strategy)

    private inner class JsModuleDeserializer(moduleDescriptor: ModuleDescriptor, klib: IrLibrary, strategy: DeserializationStrategy) :
        KotlinIrLinker.BasicIrModuleDeserializer(moduleDescriptor, klib, strategy)

    override fun createCurrentModuleDeserializer(
        moduleFragment: IrModuleFragment,
        dependencies: Collection<IrModuleDeserializer>,
        extensions: Collection<IrExtensionGenerator>
    ): IrModuleDeserializer {
        val currentModuleDeserializer = super.createCurrentModuleDeserializer(moduleFragment, dependencies, extensions)
        icData?.let { return JsCurrentModuleWithICDeserializer(currentModuleDeserializer, it) }
        return currentModuleDeserializer
    }

    private inner class JsCurrentModuleWithICDeserializer(
        private val delegate: IrModuleDeserializer,
        private val idData: Collection<SerializedIrFile>) :
        IrModuleDeserializer(delegate.moduleDescriptor) {

        private val dirtyDeclarations = mutableMapOf<IdSignature, IrSymbol>()

        override fun contains(idSig: IdSignature): Boolean {
            return idSig in dirtyDeclarations || checkIncrementalCache(idSig) || idSig in delegate
        }

        private fun checkIncrementalCache(idSig: IdSignature): Boolean {
            TODO("Check clean files")
        }

        override fun deserializeIrSymbol(idSig: IdSignature, symbolKind: BinarySymbolData.SymbolKind): IrSymbol {
            dirtyDeclarations[idSig]?.let { return it }

            if (checkIncrementalCache(idSig)) return deserializeFromIC(idSig, symbolKind)

            return delegate.deserializeIrSymbol(idSig, symbolKind)
        }

        private fun deserializeFromIC(idSig: IdSignature, symbolKind: BinarySymbolData.SymbolKind): IrSymbol {
            TODO("read from clean files")
        }

        override fun addModuleReachableTopLevel(idSig: IdSignature) {
            TODO("Not yet implemented")
        }

        override fun deserializeReachableDeclarations() {
            TODO("Not yet implemented")
        }

        override fun postProcess() {
            TODO("Not yet implemented")
        }

        override fun init() {
            super.init()
            symbolTable.forEachPublicSymbol {
                if (it.descriptor !is DeserializedDescriptor) { // public && non-deserialized should be dirty symbol
                    dirtyDeclarations[it.signature] = it
                }
            }

            TODO("...")
        }

        override val klib: IrLibrary
            get() = icDeserializer.klib

        override val moduleFragment: IrModuleFragment
            get() = delegate.moduleFragment
        override val moduleDependencies: Collection<IrModuleDeserializer>
            get() = delegate.moduleDependencies
    }

}