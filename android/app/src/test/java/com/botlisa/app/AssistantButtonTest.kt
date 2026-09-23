package com.botlisa.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers buttonFillTreatment() -- the decision behind a live bug where the
 * central button's fill stayed on whatever solid phase color was active
 * (most often LISTENING_TARGET's dark Charcoal) while a "what else?"
 * generation was running in the background, instead of a consistent light
 * grey. Reported live as "the icon background is dark gray, should be
 * light gray" while suggestions were generating.
 */
class AssistantButtonTest {

    @Test
    fun `generating always wins, even over a phase that would otherwise be solid dark`() {
        // LISTENING_TARGET is exactly the case that leaked through before this
        // fix -- hands-free stays in this phase while an explicit "what
        // else?" generates in the background.
        assertEquals(
            ButtonFillTreatment.GENERATING_NEUTRAL,
            buttonFillTreatment(UiPhase.LISTENING_TARGET, generating = true),
        )
    }

    @Test
    fun `generating wins over every other phase too`() {
        for (phase in UiPhase.entries) {
            assertEquals(
                "phase=$phase",
                ButtonFillTreatment.GENERATING_NEUTRAL,
                buttonFillTreatment(phase, generating = true),
            )
        }
    }

    @Test
    fun `idle without generating is the plain solid phase fill`() {
        assertEquals(
            ButtonFillTreatment.PHASE_SOLID,
            buttonFillTreatment(UiPhase.IDLE, generating = false),
        )
    }

    @Test
    fun `listening without generating is the plain solid phase fill`() {
        assertEquals(
            ButtonFillTreatment.PHASE_SOLID,
            buttonFillTreatment(UiPhase.LISTENING_TARGET, generating = false),
        )
        assertEquals(
            ButtonFillTreatment.PHASE_SOLID,
            buttonFillTreatment(UiPhase.LISTENING_EN, generating = false),
        )
    }

    @Test
    fun `speaking without generating is a light tint of the phase accent`() {
        assertEquals(
            ButtonFillTreatment.PHASE_LIGHT_TINT,
            buttonFillTreatment(UiPhase.SPEAKING_TRANSLATION, generating = false),
        )
        assertEquals(
            ButtonFillTreatment.PHASE_LIGHT_TINT,
            buttonFillTreatment(UiPhase.READING_RECOMMENDATION, generating = false),
        )
    }
}
