package org.futo.inputmethod.latin.diag

/**
 * Per-field privacy gate, kept free of Android types so a JVM unit test can cover it.
 *
 * Why a generation counter: events are built on other threads (suggestion coroutine, LM wait of
 * up to 350 ms, swipe decoder) and emitted later. Leaving a password field and entering a normal
 * one flips [suppressed] back to false, so a late event about the old field's word would pass an
 * `isEnabled()` check made at emit time. Each such event captures [gen] when it begins and is
 * dropped at emit time unless that generation is still current and unsuppressed.
 *
 * Fail closed: the gate starts suppressed and every field change goes through [finish] first, so
 * logging stays off until the new field has been evaluated by [start].
 */
internal class FieldGate {
    @Volatile var suppressed = true
        private set
    @Volatile var gen = 0
        private set

    /** Input finishing (or restarting): nothing may be logged until [start] evaluates the field. */
    @Synchronized fun finish() { suppressed = true; gen++ }

    /** New field evaluated. Bumps [gen] again so work begun while closed stays dropped. */
    @Synchronized fun start(suppress: Boolean) { gen++; suppressed = suppress }

    /** True if an event stamped with [g] at begin time may still be emitted. */
    fun allows(g: Int): Boolean = !suppressed && g == gen

    companion object {
        /** Password (incl. numeric), no-learning and IME_FLAG_NO_PERSONALIZED_LEARNING fields. */
        fun shouldSuppress(isPassword: Boolean, noLearning: Boolean, imeNoLearning: Boolean) =
            isPassword || noLearning || imeNoLearning
    }
}
