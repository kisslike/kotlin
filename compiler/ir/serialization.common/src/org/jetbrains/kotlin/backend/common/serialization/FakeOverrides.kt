package org.jetbrains.kotlin.backend.common.serialization

import org.jetbrains.kotlin.backend.common.DescriptorsToIrRemapper
import org.jetbrains.kotlin.backend.common.WrappedDescriptorPatcher
import org.jetbrains.kotlin.backend.common.ir.ir2string
import org.jetbrains.kotlin.backend.common.serialization.signature.IdSignatureSerializer
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities.INHERITED
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrPropertyImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrValueParameterImpl
import org.jetbrains.kotlin.ir.descriptors.*
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.buildSimpleType
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid

class FakeOverrideBuilder(val symbolTable: SymbolTable, val signaturer: IdSignatureSerializer, val irBuiltIns: IrBuiltIns) {
    //private val needFakeOverrides = mutableListOf<IrClass>()
    private val haveFakeOverrides = mutableSetOf<IrClass>()
    private val deserializationStateForClass = mutableMapOf<IrClass, KotlinIrLinker.DeserializationState<IdSignature>>()

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
        val copier = DeepCopyIrTreeWithSymbolsForFakeOverrides(substitutionMap, superType, clazz)

        //require(member is IrOverridableDeclaration<*>)
        require(member is IrProperty || member is IrSimpleFunction)
        val deepCopyFakeOverride = copier.copy(member) as IrDeclaration
        //require(deepCopyFakeOverride is IrOverridableDeclaration<*>)
        require(deepCopyFakeOverride is IrProperty || deepCopyFakeOverride is IrSimpleFunction)
        deepCopyFakeOverride.parent = clazz
        require(deepCopyFakeOverride is IrSymbolOwner)
        assert(deepCopyFakeOverride.symbol.owner == deepCopyFakeOverride)
        assert((deepCopyFakeOverride.symbol.descriptor as? WrappedDeclarationDescriptor<*>)?.owner == deepCopyFakeOverride)

        return deepCopyFakeOverride
    }


    private val IrDeclaration.modality get() = when(this) {
        is IrProperty -> this.modality
        is IrSimpleFunction -> this.modality
        else -> error("Could not take modality of ${this.render()}")
    }

    // TODO: make me non-recursive.
    fun buildFakeOverridesForClass(clazz: IrClass) {
        if (haveFakeOverrides.contains(clazz)) return

        val superTypes = clazz.superTypes

        val superClasses = superTypes.map {
            it.classifierOrNull?.owner as IrClass?
        }.filterNotNull()

        superClasses.forEach {
            buildFakeOverridesForClass(it)
            haveFakeOverrides.add(it)
        }

        println("\n\nWould bring fake overrides to ${ir2string(clazz)}:")
        superTypes.forEach {
            println("\tSUPERTYPE: ${it.render()}")
        }

        val overridePairs = superTypes.flatMap { superType ->
            val superClass = superType.classifierOrNull?.owner as IrClass? // TODO: What if there's no class?
            val overriddenMembers = superClass!!.declarations
                .filter { it is IrSimpleFunction || it is IrProperty }
                //.filterIsInstance<IrOverridableDeclaration<*>>()
                .filter { (it as IrSymbolOwner).symbol.isPublicApi }
            val overrides = overriddenMembers.map { overridden ->
                val override = fakeOverrideMember(superType, overridden, clazz)
                Triple(overridden, override, signaturer.composePublicIdSignature(override))
            }
            overrides
        }

        val existingMembers = clazz.declarations
            //.filterIsInstance<IrOverridableDeclaration<*>>()
            .filter{ it is IrProperty || it is IrSimpleFunction }
            .filter { (it as IrSymbolOwner).symbol.isPublicApi }

        val existingIdSignatures = existingMembers
            .map { signaturer.composePublicIdSignature(it) }

        println("DESER: ${existingMembers.map {ir2string(it)}}")
        println("DESER: $existingIdSignatures")

        val uniqOverridePairs = overridePairs.groupBy(
            { it.third },
            { Pair(it.first, it.second) }
        ).filter { entry ->
            entry.key !in existingIdSignatures
        }.filter { entry ->
            print("OVERRIDDEN:");  println(entry.value.map { it.first.render() } )
            print("NON ABSTRACT first"); println(entry.value.firstOrNull{it.first.modality != Modality.ABSTRACT}?.first?.render())
            print("NON ABSTRACT second"); println(entry.value.firstOrNull{it.first.modality != Modality.ABSTRACT}?.second?.render())
            val fake =
                entry.value.firstOrNull{it.first.modality != Modality.ABSTRACT}?.second ?:
                entry.value.first().second
            existingMembers.none { it.overrides(fake) }
        }

        println("SYNTH: ${uniqOverridePairs.keys}")


        val fakeOverrides = uniqOverridePairs.values
            .map {
                val singleFakeOverride = it.firstOrNull{it.first.modality != Modality.ABSTRACT}?.second ?: it.first().second
                when (singleFakeOverride) {
                    is IrFunctionImpl -> {
                        val overriddenSymbols = it.map { (it.first as IrSimpleFunction).symbol }
                        singleFakeOverride.overriddenSymbols.addAll(overriddenSymbols)
                        println("overriddenSymbols for ${singleFakeOverride.render()}")
                        println(overriddenSymbols.map{it.owner.render()})
                    }
                    is IrProperty ->
                        {
                            // TODO: what to do here?
                        }
                    else -> error("No overridden symbols for ${ir2string(singleFakeOverride)}")
                }
                singleFakeOverride
            }

        println("SYNTH: ${fakeOverrides.map {ir2string(it)}}")

        fun redelegateFunction(fake: IrSimpleFunction) {
            val properSignature = signaturer.composePublicIdSignature(fake)

            val deserializedSymbol =
                symbolTable.referenceSimpleFunctionFromLinker(WrappedSimpleFunctionDescriptor(), properSignature)

            (fake.symbol as? IrDelegatingSimpleFunctionSymbolImpl)?.let {
                println("Redelegating ${fake.nameForIrSerialization} to $deserializedSymbol")
                it.delegate = deserializedSymbol
            } ?: error("Somebody else's fake override: in ${ir2string(clazz)} ${ir2string(fake)} ${fake.symbol}")

            if (!deserializedSymbol.isBound) {
                println("binding $deserializedSymbol to $fake ${ir2string(fake)}")
                deserializedSymbol.bind(fake)
                symbolTable.rebindSimpleFunction(properSignature, fake)
            } else println("symbol is already bound to ${ir2string(deserializedSymbol.owner)}")

            (deserializedSymbol.descriptor as? WrappedSimpleFunctionDescriptor)?.let {
                if (!it.isBound()) it.bind(fake)
            }
        }

        fun redelegateProperty(fake: IrProperty) {
            val properSignature = signaturer.composePublicIdSignature(fake)

            val deserializedSymbol =
                symbolTable.referencePropertyFromLinker(WrappedPropertyDescriptor(), properSignature)

            (fake.symbol as? IrDelegatingPropertySymbolImpl)?.let {
                println("Redelegating ${fake.nameForIrSerialization} to $deserializedSymbol")
                it.delegate = deserializedSymbol
            } ?: error("Somebody else's fake override: in ${ir2string(clazz)} ${ir2string(fake)} ${fake.symbol}")

            if (!deserializedSymbol.isBound) {
                println("binding $deserializedSymbol to $fake ${ir2string(fake)}")
                deserializedSymbol.bind(fake)
                symbolTable.rebindProperty(properSignature, fake)
            } else println("symbol is already bound to ${ir2string(deserializedSymbol.owner)}")

            (deserializedSymbol.descriptor as? WrappedPropertyDescriptor)?.let {
                if (!it.isBound()) it.bind(fake)
            }

            fake.getter?.let { redelegateFunction(it) }
            fake.setter?.let { redelegateFunction(it) }
        }

        fakeOverrides.forEach { fake ->
            when (fake) {
                is IrSimpleFunction -> redelegateFunction(fake)
                is IrProperty -> redelegateProperty( fake)
            }
            clazz.declarations.add(fake)
        }
    }

    fun IrDeclaration.overrides(other: IrDeclaration): Boolean {
        when (this) {
            is IrSimpleFunction -> {
                if (other !is IrSimpleFunction) return false
                if (this.name != other.name) return false
                this.valueParameters.forEachIndexed { index, parameter ->
                    if (!other.valueParameters[index].type.equals(parameter.type)) return false
                }
                if (this.typeParameters.size != other.typeParameters.size) return false
                if (!this.returnType.isSubtypeOf(other.returnType, irBuiltIns)) return false

                return true
            }
            is IrProperty -> {
                if (other !is IrProperty) return false
                if (this.name != other.name) return false
                // TODO: We assume getter always exists for a property here.
                if (!this.getter!!.returnType.isSubtypeOf(other.getter!!.returnType, irBuiltIns)) return false

                return true
            }
            else -> return false
        }
    }

    fun provideFakeOverrides(module: IrModuleFragment) {
        module.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }
            override fun visitClass(declaration: IrClass) {
                if (declaration.symbol.isPublicApi) {
                    buildFakeOverridesForClass(declaration)
                    haveFakeOverrides.add(declaration)
                }
                super.visitClass(declaration)
            }
            override fun visitFunction(declaration: IrFunction) {
                // Don't go for local classes
                return
            }
        })
    }
}

private fun IrType.render(): String = RenderIrElementVisitor().renderType(this)


// This is basicly modelled after the inliner copier.
class DeepCopyIrTreeWithSymbolsForFakeOverrides(
    val typeArguments: Map<IrTypeParameterSymbol, IrType?>?,
    val superType: IrType,
    val parent: IrClass
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

    private inner class FakeOverrideTypeRemapper(
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

    private class FakeOverrideSymbolRemapperImpl(descriptorsRemapper: DescriptorsRemapper) : DeepCopySymbolRemapper(descriptorsRemapper) {

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

    private val symbolRemapper = FakeOverrideSymbolRemapperImpl(DescriptorsToIrRemapper)
    private val copier =
        DeepCopyIrTreeForFakeOverrides(symbolRemapper, FakeOverrideTypeRemapper(symbolRemapper, typeArguments), SymbolRenamer.DEFAULT, parent)
}

class DeepCopyIrTreeForFakeOverrides(
    val symbolRemapper: SymbolRemapper,
    val typeRemapper: TypeRemapper,
    val symbolRenamer: SymbolRenamer,
    val destinationClass: IrClass
) : DeepCopyIrTreeWithSymbols(symbolRemapper, typeRemapper, symbolRenamer) {

    private fun <T : IrFunction> T.transformFunctionChildren(declaration: T): T =
        apply {
            transformAnnotations(declaration)
            copyTypeParametersFrom(declaration)
            //val dispatchReceiver =
            typeRemapper.withinScope(this) {
/*
                val superDispatchReceiver = declaration.dispatchReceiverParameter!!
                val dispatchReceiverSymbol = IrValueParameterSymbolImpl(WrappedValueParameterDescriptor())
                val dispatchReceiverType = destinationClass.defaultType
                dispatchReceiverParameter = IrValueParameterImpl(
                    superDispatchReceiver.startOffset,
                    superDispatchReceiver.endOffset,
                    superDispatchReceiver.origin,
                    dispatchReceiverSymbol,
                    superDispatchReceiver.name,
                    superDispatchReceiver.index,
                    dispatchReceiverType,
                    null,
                    superDispatchReceiver.isCrossinline,
                    superDispatchReceiver.isNoinline
                )

 */
                // Should fake override's receiver be the current class is an open question.
                dispatchReceiverParameter = declaration.dispatchReceiverParameter?.transform()
                extensionReceiverParameter = declaration.extensionReceiverParameter?.transform()
                returnType = typeRemapper.remapType(declaration.returnType)
                declaration.valueParameters.transformTo(valueParameters)
            }
        }

    override fun visitSimpleFunction(declaration: IrSimpleFunction): IrSimpleFunction =
        IrFunctionImpl(
            declaration.startOffset, declaration.endOffset,
            IrDeclarationOrigin.FAKE_OVERRIDE,
            (wrapInDelegatedSymbol(symbolRemapper.getDeclaredFunction(declaration.symbol)) as IrSimpleFunctionSymbol),
            symbolRenamer.getFunctionName(declaration.symbol),
            //INHERITED,
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
            //declaration.overriddenSymbols.mapTo(overriddenSymbols) {
            //   symbolRemapper.getReferencedFunction(it) as IrSimpleFunctionSymbol
            //}
            transformFunctionChildren(declaration)
        }

    override fun visitProperty(declaration: IrProperty): IrProperty =
        IrPropertyImpl(
            declaration.startOffset, declaration.endOffset,
            IrDeclarationOrigin.FAKE_OVERRIDE,
            (wrapInDelegatedSymbol(symbolRemapper.getDeclaredProperty(declaration.symbol)) as IrPropertySymbol),
            declaration.name,
            //INHERITED,
            declaration.visibility,
            declaration.modality,
            isVar = declaration.isVar,
            isConst = declaration.isConst,
            isLateinit = declaration.isLateinit,
            isDelegated = declaration.isDelegated,
            isExpect = declaration.isExpect,
            isExternal = declaration.isExternal
        ).apply {
            transformAnnotations(declaration)
            //this.backingField = declaration.backingField?.transform()
            this.getter = declaration.getter?.transform()
            this.setter = declaration.setter?.transform()
            //this.backingField?.let {
            //    it.correspondingPropertySymbol = symbol
            //}
        }
}