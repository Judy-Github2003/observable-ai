package logic.e3.pack02

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Engineering contracts, not evidence of autonomous scientific success. */
class SD03R31EnumerationContractTest {
    @Test fun `preregistered quota equals original global limit`() {
        val q = MRX2BR31Quotas()
        assertEquals(30_000, q.total)
        assertEquals(256, q.limit(MRX2BR31Family.LITERAL))
        assertEquals(1024, q.limit(MRX2BR31Family.AND))
        assertEquals(23000, q.limit(MRX2BR31Family.OR))
        assertEquals(5720, q.limit(MRX2BR31Family.IF))
    }

    @Test fun `counter separation and totals are losslessly accounted`() {
        val c = MRX2BR31Counters()
        c.forFamily(MRX2BR31Family.OR).apply {
            attempted = 7; admitted = 2; familyQuotaRejected = 5
        }
        c.forFamily(MRX2BR31Family.IF).apply {
            attempted = 6; admitted = 1; duplicateRejected = 3; nodeRejected = 2
        }
        assertEquals(13L, c.attempts)
        assertEquals(3L, c.admitted)
        assertTrue(c.byFamily.keys.containsAll(MRX2BR31Family.entries))
    }
}
