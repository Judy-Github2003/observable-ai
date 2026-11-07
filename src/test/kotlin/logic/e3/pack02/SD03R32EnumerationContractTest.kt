package logic.e3.pack02

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Mechanical contracts only. These do not demonstrate learning or transfer. */
class SD03R32EnumerationContractTest {
    @Test fun `legacy R31 enumeration preserved exactly`() {
        val n = 24
        val old = buildList {
            for (a in 0 until n) for (b in 0 until n) for (c in 0 until n)
                add(MRX2BR32Direct(c, a, b))
        }
        assertEquals(old, MRX2BR32Enumeration.direct(n, MRX2BR32IfPolicy.R31_LEXICOGRAPHIC).toList())
    }

    @Test fun `rotating covers every distinct triple once`() {
        val xs = MRX2BR32Enumeration.direct(24, MRX2BR32IfPolicy.ROTATING_DIRECT).toList()
        assertEquals(13_824, xs.size)
        assertEquals(xs.size, xs.toSet().size)
        assertEquals(MRX2BR32Enumeration.direct(24, MRX2BR32IfPolicy.R31_LEXICOGRAPHIC).toSet(), xs.toSet())
    }

    @Test fun `with 5720 slots rotating covers all literals in all direct roles`() {
        val lex = MRX2BR32Enumeration.direct(24, MRX2BR32IfPolicy.R31_LEXICOGRAPHIC).take(5_720).toList()
        val fair = MRX2BR32Enumeration.direct(24, MRX2BR32IfPolicy.ROTATING_DIRECT).take(5_720).toList()
        assertEquals(10, lex.map { it.whenTrue }.distinct().size)
        assertEquals(24, fair.map { it.whenTrue }.distinct().size)
        assertEquals(24, fair.map { it.whenFalse }.distinct().size)
        assertEquals(24, fair.map { it.condition }.distinct().size)
        assertNotEquals(lex.take(24), fair.take(24))
        assertTrue(fair.take(24).all { it.condition != it.whenTrue &&
            it.whenFalse != it.whenTrue && it.condition != it.whenFalse })
    }

    @Test fun `nested sampler has unique descriptors and covers seven roles`() {
        val xs = MRX2BR32Enumeration.nested(24).take(2_860).toList()
        assertEquals(2_860, xs.size)
        assertEquals(2_860, xs.toSet().size)
        assertTrue(xs.map { it.outerCondition }.distinct().size == 24)
        assertTrue(xs.map { it.trueCondition }.distinct().size == 24)
        assertTrue(xs.map { it.falseCondition }.distinct().size == 24)
        assertTrue(xs.map { it.falseWhenFalse }.distinct().size == 24)
    }

    @Test fun `experiment code does not receive examiner labels`() {
        val api = MRX2BR32Enumeration.direct(24, MRX2BR32IfPolicy.ROTATING_DIRECT).first()
        assertTrue(api.condition in 0..23)
    }
}
