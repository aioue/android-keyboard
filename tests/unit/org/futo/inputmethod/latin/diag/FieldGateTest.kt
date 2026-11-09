package org.futo.inputmethod.latin.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldGateTest {
    @Test fun startsClosed() {
        val g = FieldGate()
        assertTrue(g.suppressed)
        assertFalse(g.allows(g.gen))
    }

    @Test fun eventFromPasswordFieldIsDroppedInNextNormalField() {
        val g = FieldGate()
        g.start(true)
        val begunInPassword = g.gen
        g.finish()
        g.start(false) // next field is a normal one
        assertFalse(g.suppressed)
        assertFalse(g.allows(begunInPassword))
        assertTrue(g.allows(g.gen))
    }

    @Test fun closedBetweenFinishAndStart() {
        val g = FieldGate()
        g.start(false)
        val gen = g.gen
        assertTrue(g.allows(gen))
        g.finish()
        assertFalse(g.allows(gen))
        assertFalse(g.allows(g.gen)) // work begun while closed stays dropped
        g.start(false)
        assertFalse(g.allows(gen))
    }

    @Test fun suppressedFieldAllowsNothing() {
        val g = FieldGate()
        g.start(true)
        assertFalse(g.allows(g.gen))
    }

    @Test fun suppressionFlags() {
        assertTrue(FieldGate.shouldSuppress(true, false, false))
        assertTrue(FieldGate.shouldSuppress(false, true, false))
        assertTrue(FieldGate.shouldSuppress(false, false, true))
        assertEquals(false, FieldGate.shouldSuppress(false, false, false))
    }
}
