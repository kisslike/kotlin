// FIR_IDENTICAL
/*
 * SPEC LINKS (spec version: 0.1-152, test type: pos):
 * PRIMARY LINKS: expressions, when-expression -> paragraph 5 -> sentence 1
 * expressions, when-expression -> paragraph 9 -> sentence 2
 */

fun foo(x: Int) {
    when (x) {
        2 -> {}
        3 -> {}
    }
}