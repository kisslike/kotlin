// !DIAGNOSTICS: -UNUSED_VARIABLE
// !CHECK_TYPE
/*
 * KOTLIN DIAGNOSTICS SPEC TEST (POSITIVE)
 *
 * SPEC VERSION: 0.1-220
 * MAIN LINK: expressions, call-and-property-access-expressions, callable-references -> paragraph 11 -> sentence 3
 * PRIMARY LINKS: expressions, call-and-property-access-expressions, callable-references -> paragraph 1441 -> sentence 344
 * expressions, call-and-property-access-expressions, callable-references -> paragraph 1111 -> sentence 1111
 * SECONDARY LINKS: expressions, call-and-property-access-expressions, callable-references -> paragraph 222 -> sentence 22222
 * expressions, call-and-property-access-expressions, callable-references -> paragraph 3333 -> sentence 333
 */

import kotlin.reflect.KFunction0

fun test() {
    val a = if (true) {
        val x = 1
        "".length
        ::foo
    } else {
        ::foo
    }
    a checkType {  _<KFunction0<Int>>() }
}

fun foo(): Int = 0