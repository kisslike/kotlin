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
}