package org.jetbrains.kotlin.backend.common.serialization

import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeCheckerContext
import org.jetbrains.kotlin.types.AbstractTypeChecker
import org.jetbrains.kotlin.types.AbstractTypeCheckerContext
import org.jetbrains.kotlin.types.model.TypeConstructorMarker

open class IrTypeCheckerContextWithAdditionalAxioms(
    override val irBuiltIns: IrBuiltIns,
    val firstParameters: List<IrTypeParameter>,
    val secondParameters: List<IrTypeParameter>
) : IrTypeCheckerContext(irBuiltIns) {
    init {
        assert(firstParameters.size == secondParameters.size) {
            "Should be the same number of type parameters: $firstParameters vs $secondParameters"
        }
    }

    val firstTypeParameterConstructors = firstParameters.map { it.symbol }
    val secondTypeParameterConstructors = secondParameters.map { it.symbol }
    val matchingTypeConstructors = firstTypeParameterConstructors.zip(secondTypeParameterConstructors).toMap()

    override fun areEqualTypeConstructors(a: TypeConstructorMarker, b: TypeConstructorMarker): Boolean {
        if (super.isEqualTypeConstructors(a, b)) return true
        if (matchingTypeConstructors[a] == b || matchingTypeConstructors[b] == a) return true
        return false
    }

    override fun isEqualTypeConstructors(c1: TypeConstructorMarker, c2: TypeConstructorMarker): Boolean {
        if (super.isEqualTypeConstructors(c1, c2)) return true
        if (matchingTypeConstructors[c1] == c2 || matchingTypeConstructors[c2] == c1) return true
        return false
    }
}