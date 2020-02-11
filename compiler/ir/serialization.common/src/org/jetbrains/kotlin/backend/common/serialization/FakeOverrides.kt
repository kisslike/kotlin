package org.jetbrains.kotlin.backend.common.serialization

import org.jetbrains.kotlin.backend.common.DescriptorsToIrRemapper
import org.jetbrains.kotlin.backend.common.WrappedDescriptorPatcher
import org.jetbrains.kotlin.backend.common.ir.ir2string
import org.jetbrains.kotlin.backend.common.serialization.signature.IdSignatureSerializer
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.TypeAliasDescriptor
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.descriptors.WrappedSimpleFunctionDescriptor
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionPublicSymbolImpl
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.buildSimpleType
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.multiplatform.findActuals

class FakeOverrideBuilder(val symbolTable: SymbolTable, val signaturer: IdSignatureSerializer, val globalDeserializationState: KotlinIrLinker.DeserializationState<IdSignature>) {
    private val needFakeOverrides = mutableListOf<IrClass>()
    private val haveFakeOverrides = mutableListOf<IrClass>()
    private val deserializationStateForClass = mutableMapOf<IrClass, KotlinIrLinker.DeserializationState<IdSignature>>()

    fun needsFakeOverride(clazz: IrClass, deserializationState: KotlinIrLinker.DeserializationState<IdSignature>) {
 /*       if (!clazz.symbol.isPublicApi) return
        println("NEEDS FAKE OVERRIDE: ${clazz.symbol.descriptor} ${clazz.symbol.signature}")
        deserializationStateForClass.put(clazz, deserializationState)
        needFakeOverrides += clazz
   */
        clazz.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }
            override fun visitClass(declaration: IrClass) {
                println("visiting: ${declaration.name} ${declaration.symbol}")
                if  (declaration.symbol.isPublicApi) {
                    println("NEEDS FAKE OVERRIDE: ${clazz.symbol.descriptor} ${clazz.symbol.signature}")

                    deserializationStateForClass.put(declaration, deserializationState)
                    needFakeOverrides += declaration
                }
                super.visitClass(declaration)
            }
        })

    }
    private fun fakeOverrideMember(superType: IrType, member: IrDeclaration, clazz: IrClass): IrDeclaration {
        if (superType !is IrSimpleType) error("superType is $superType, expected IrSimpleType")
        val classifier = superType.classifier
        if (classifier !is IrClassSymbol) error("superType classifier is not IrClassSymbol: ${classifier}")


        val typeParameters = classifier.owner.typeParameters.map { it.symbol }
        val typeArguments = superType.arguments.map { it as IrSimpleType } // TODO: the cast should not be here

        assert(typeParameters.size == typeArguments.size) {
            "typeParameters = $typeParameters size != typeArguments = $typeArguments size "
        }

        val substitutionMap = typeParameters.zip(typeArguments).toMap()
        val copier = DeepCopyIrTreeWithSymbolsForFakeOverrides(substitutionMap, clazz)

        require(member is IrOverridableDeclaration<*>)
        val deepCopyFakeOverride = copier.copy(member)
        require(deepCopyFakeOverride is IrOverridableDeclaration<*>)
        deepCopyFakeOverride.parent = clazz
        require(deepCopyFakeOverride is IrSymbolDeclaration<*>)
        assert(deepCopyFakeOverride.symbol.owner == deepCopyFakeOverride)
        assert((deepCopyFakeOverride.symbol.descriptor as? WrappedSimpleFunctionDescriptor)?.owner == deepCopyFakeOverride)

        return deepCopyFakeOverride
    }

    private val IrDeclaration.isFakeOverride: Boolean
        get () = when (this) {
            is IrSimpleFunction -> this.isFakeOverride
            is IrProperty -> this.isFakeOverride
            is IrField -> this.isFakeOverride
            else -> false
        }

    private val IrDeclaration.overriddenSymbols: List<IrSymbol>
        get() = when (this) {
            is IrSimpleFunction -> this.overriddenSymbols
            is IrProperty -> this.overriddenSymbols
            is IrField -> this.overriddenSymbols
            else -> emptyList()
        }

    // TODO: make me non-recursive.
    private fun buildFakeOverridesForClass(clazz: IrClass) {
        if (haveFakeOverrides.contains(clazz)) return
        if (clazz.descriptor.fqNameSafe.toString().startsWith("kotlin.Function") ||
            clazz.descriptor.fqNameSafe.toString().startsWith("kotlin.reflect.KFunction") ||
            clazz.descriptor.fqNameSafe.toString().startsWith("kotlin.reflect.KProperty") ||
            clazz.descriptor.fqNameSafe.toString().startsWith("kotlin.reflect.KMutableProperty") ||
            clazz.descriptor.fqNameSafe.toString().startsWith("kotlin.native.internal.KMutableProperty") ||
            clazz.descriptor.fqNameSafe.toString().startsWith("kotlin.native.internal.KLocalDelegatedProperty") ||
            clazz.descriptor.fqNameSafe.toString().startsWith("kotlin.native.internal.KLocalDelegatedMutableProperty")

        ) return

        clazz.superTypes.forEach {
            it.classOrNull?.let {
                if (it.isBound) {
                    val superClass = it.owner
                    buildFakeOverridesForClass(superClass)
                    haveFakeOverrides.add(superClass)
                } else println("Somehow unbound symbol for ${it.descriptor}")
            }
        }

        println("\n\nWould bring fake overrides to ${ir2string(clazz)}:")

        val allOverridden = clazz.declarations
            .filter { it is IrSimpleFunction }
            .filterIsInstance<IrOverridableDeclaration<*>>()
            .filterNot { it.isFakeOverride }
            .flatMap { (it as IrSimpleFunction).overriddenSymbols }
            .map { if (it.isBound) it.owner else null }
        println("\tOverridden symbol declarations: ${allOverridden.map { it?.name }}")

        val nullableComparator = Comparator<Long?> { a, b ->
            when {
                (a == null) && (b == null) -> 0
                a == null -> -1
                b == null -> 1
                a > b -> 1
                a < b -> -1
                else -> 0
            }
        }


        clazz.declarations.filter {
            it is IrFunction// && it.isFakeOverride
        }.also {
            println("\t\t\tDESER: ${it.map{ir2string(it)}}")
        }.map {
            try {
                signaturer.composePublicIdSignature(it)
            } catch (e: Throwable) {
                println("processed exception: " + e.message)
                null
            }
        }/*.sortedWith(nullableComparator)*/.let {
            println("\t\t\tDESER: $it")
        }

        val newFakeOverrides = mutableListOf<IrDeclaration>()
        clazz.superTypes.forEach { superType ->
            superType.classOrNull?.let {
                if (it.isBound) {
                    val superClass = it.owner
                    println("\tfrom ${ir2string(superClass)}:")
                    superClass.declarations
                        .filterIsInstance<IrOverridableDeclaration<*>>()
                        .filter { (it as IrSymbolDeclaration<*>).symbol.isPublicApi }
                        .filterNot { allOverridden.filterNotNull().contains(it) }
                        .map {
                            fakeOverrideMember(superType, it, clazz).also {
                                newFakeOverrides.add(it)
                            }
                        }.also {
                            println("\t\t\tSYNTH: ${it.map{ir2string(it)}}")
                        }.map {
                            signaturer.composePublicIdSignature(it)
                        }/*.sortedWith(nullableComparator)*/.let {
                            println("\t\t\tSYNTH: $it")
                        }
                } else println("ditto")
            }
        }

        newFakeOverrides
            .distinctBy { signaturer.composePublicIdSignature(it) }
            .forEach { fake ->
                require(fake is IrSimpleFunction)
                val properSignature = signaturer.composePublicIdSignature(fake)
                val deserializationState = deserializationStateForClass[clazz.findTopLevelDeclaration()] ?: error("No state for ${ir2string(clazz)}")

                val deserializedSymbol = deserializationState.deserializedSymbols[properSignature]
                if (deserializedSymbol != null) {
                    if ((fake as IrFunction).symbol !is IrDelegatingSimpleFunctionSymbolImpl) {
                        error("Somebody elses fake override: in ${ir2string(clazz)} ${ir2string(fake)} ${(fake as IrFunction).symbol}")
                    }
                    println("Redelegating ${(fake as IrFunction).name.asString()} to $deserializedSymbol")
                    ((fake as IrFunction).symbol as IrDelegatingSimpleFunctionSymbolImpl).delegate =
                        deserializedSymbol as IrSimpleFunctionSymbol
                    println("binding (state for class) in ${ir2string(clazz)} fake ${ir2string(fake)}")
                    deserializedSymbol.bind(fake)
                    (deserializedSymbol.descriptor as? WrappedSimpleFunctionDescriptor)?.bind(fake)

                } else {
                    println("No symbols for ${(fake as IrFunction).name.asString()} yet, couldn't redelegate.")
                    println("Placing ${(fake as IrFunction).symbol} ${signaturer.composePublicIdSignature(fake)} to state ${deserializationStateForClass[clazz]}")
                    val symbolWithProperSignature = symbolTable.referenceSimpleFunctionFromLinker((fake as IrFunction).symbol.descriptor, properSignature)
                    ((fake as IrFunction).symbol as IrDelegatingSimpleFunctionSymbolImpl).delegate = symbolWithProperSignature
                    deserializationState.deserializedSymbols[properSignature] = symbolWithProperSignature
                    println("binding $symbolWithProperSignature to ${ir2string(fake)}")
                    symbolWithProperSignature.bind(fake)
                }
                clazz.declarations.add(fake)
                println("Declaring ${fake.symbol} ${fake.symbol.signature} to be ${ir2string(fake)}")
                symbolTable.declareSimpleFunctionFromLinker(fake.symbol.descriptor, fake.symbol.signature, { fake })
            }
    }

    fun buildFakeOverrides() {
        needFakeOverrides.forEach {
            buildFakeOverridesForClass(it)
            haveFakeOverrides.add(it)
        }
        needFakeOverrides.clear()
    }
}

private fun IrType.render(): String = RenderIrElementVisitor().renderType(this)


// This is basicly modelled after the inliner copier.
class DeepCopyIrTreeWithSymbolsForFakeOverrides(
    val typeArguments: Map<IrTypeParameterSymbol, IrType?>?,
    val parent: IrDeclarationParent?
) {

    fun copy(irElement: IrElement): IrElement {
        // Create new symbols.
        irElement.acceptVoid(symbolRemapper)

        // Make symbol remapper aware of the callsite's type arguments.
        symbolRemapper.typeArguments = typeArguments

        // Copy IR.
        val result = irElement.transform(copier, data = null)

        // Bind newly created IR with wrapped descriptors.
        result.acceptVoid(WrappedDescriptorPatcher)

        result.patchDeclarationParents(parent)
        return result
    }

    private inner class InlinerTypeRemapper(
        val symbolRemapper: SymbolRemapper,
        val typeArguments: Map<IrTypeParameterSymbol, IrType?>?
    ) : TypeRemapper {

        override fun enterScope(irTypeParametersContainer: IrTypeParametersContainer) {}

        override fun leaveScope() {}

        private fun remapTypeArguments(arguments: List<IrTypeArgument>) =
            arguments.map { argument ->
                (argument as? IrTypeProjection)?.let { makeTypeProjection(remapType(it.type), it.variance) }
                    ?: argument
            }

        override fun remapType(type: IrType): IrType {
            if (type !is IrSimpleType) return type

            val substitutedType = typeArguments?.get(type.classifier)

            if (substitutedType is IrDynamicType) return substitutedType

            if (substitutedType is IrSimpleType) {
                return substitutedType.buildSimpleType {
                    kotlinType = null
                    hasQuestionMark = type.hasQuestionMark or substitutedType.isMarkedNullable()
                }
            }

            return type.buildSimpleType {
                kotlinType = null
                classifier = symbolRemapper.getReferencedClassifier(type.classifier)
                arguments = remapTypeArguments(type.arguments)
                annotations = type.annotations.map { it.transform(copier, null) as IrConstructorCall }
            }
        }
    }

    private class SymbolRemapperImpl(descriptorsRemapper: DescriptorsRemapper) : DeepCopySymbolRemapper(descriptorsRemapper) {

        var typeArguments: Map<IrTypeParameterSymbol, IrType?>? = null
            set(value) {
                if (field != null) return
                field = value?.asSequence()?.associate {
                    (getReferencedClassifier(it.key) as IrTypeParameterSymbol) to it.value
                }
            }

        override fun getReferencedClassifier(symbol: IrClassifierSymbol): IrClassifierSymbol {
            val result = super.getReferencedClassifier(symbol)
            if (result !is IrTypeParameterSymbol)
                return result
            return typeArguments?.get(result)?.classifierOrNull ?: result
        }
    }

    private val symbolRemapper = SymbolRemapperImpl(DescriptorsToIrRemapper)
    private val copier =
        DeepCopyIrTreeForFakeOverrides(symbolRemapper, InlinerTypeRemapper(symbolRemapper, typeArguments), SymbolRenamer.DEFAULT)
}

class DeepCopyIrTreeForFakeOverrides(
    val symbolRemapper: SymbolRemapper,
    val typeRemapper: TypeRemapper,
    val symbolRenamer: SymbolRenamer
) : DeepCopyIrTreeWithSymbols(symbolRemapper, typeRemapper, symbolRenamer) {

    private fun <T : IrFunction> T.transformFunctionChildren(declaration: T): T =
        apply {
            transformAnnotations(declaration)
            copyTypeParametersFrom(declaration)
            typeRemapper.withinScope(this) {
                dispatchReceiverParameter = declaration.dispatchReceiverParameter?.transform()
                extensionReceiverParameter = declaration.extensionReceiverParameter?.transform()
                returnType = typeRemapper.remapType(declaration.returnType)
                declaration.valueParameters.transformTo(valueParameters)
                // And there are no bodies for fake overrides.
            }
        }

    override fun visitSimpleFunction(declaration: IrSimpleFunction): IrSimpleFunction =
        IrFunctionImpl(
            declaration.startOffset, declaration.endOffset,
            IrDeclarationOrigin.FAKE_OVERRIDE,
            (wrapInDelegatedSymbol(symbolRemapper.getDeclaredFunction(declaration.symbol)) as IrSimpleFunctionSymbol).also {
                println("Wrapping ${declaration.nameForIrSerialization} into delegating symbol")
            },
            symbolRenamer.getFunctionName(declaration.symbol),
            declaration.visibility,
            declaration.modality,
            declaration.returnType,
            isInline = declaration.isInline,
            isExternal = declaration.isExternal,
            isTailrec = declaration.isTailrec,
            isSuspend = declaration.isSuspend,
            isExpect = declaration.isExpect,
            isFakeOverride = true,
            isOperator = declaration.isOperator
        ).apply {
            declaration.overriddenSymbols.mapTo(overriddenSymbols) {
                symbolRemapper.getReferencedFunction(it) as IrSimpleFunctionSymbol
            }
            transformFunctionChildren(declaration)
        }
}