package com.botlisa.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers shouldRegenerateWhatElseFor() and whatElseDisplayUtterance() -- the
 * decisions behind the most disruptive live bug in the app: repeating "what
 * else?" sometimes continued reading the existing suggestion list, and
 * sometimes silently regenerated a brand new one from scratch, depending on
 * whether hands-free happened to pick up any incidental speech (the
 * caregiver reading a suggestion back to the child, ambient noise, ...) in
 * between two asks. The fix holds the list in place once it's actually been
 * requested, instead of letting every newly-heard utterance reset it.
 */
class WhatElseHoldTest {

    @Test
    fun `a fresh utterance before anything was asked regenerates as before`() {
        assertTrue(shouldRegenerateWhatElseFor(whatElseRequested = false))
    }

    @Test
    fun `an utterance heard while a list is being held does not regenerate`() {
        // This is the exact bug: whatElseRequested stays true for the whole
        // time the caregiver is cycling through a list they already asked
        // for, so any utterance landing in that window must not blow it away.
        assertFalse(shouldRegenerateWhatElseFor(whatElseRequested = true))
    }

    @Test
    fun `display heading follows the live utterance before a list is held`() {
        assertEquals(
            "новое",
            whatElseDisplayUtterance(
                whatElseRequested = false,
                generatingForUtterance = "старое",
                lastUtterance = "новое",
            ),
        )
    }

    @Test
    fun `display heading pins to the held list's own utterance, not later incidental speech`() {
        // generatingForUtterance is what the currently-held list was actually
        // generated for; lastUtterance may have already drifted ahead to
        // something heard afterward. The heading must not drift with it --
        // otherwise it would say "Related phrases for: <newer phrase>" over
        // a list that's still the older phrase's suggestions.
        assertEquals(
            "давай наденем твою пижамку",
            whatElseDisplayUtterance(
                whatElseRequested = true,
                generatingForUtterance = "давай наденем твою пижамку",
                lastUtterance = "подними ручки вверх",
            ),
        )
    }

    @Test
    fun `display heading falls back to lastUtterance if nothing was ever generated for`() {
        assertEquals(
            "новое",
            whatElseDisplayUtterance(
                whatElseRequested = true,
                generatingForUtterance = null,
                lastUtterance = "новое",
            ),
        )
    }
}
