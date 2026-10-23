package com.sportpredi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenFootballFixtureIdTest {
    @Test
    fun fixtureIdsAreStableAndUseLongRangeToAvoidKnownIntCollisions() {
        val greekFixture =
            "fixture:openfootball:gr.1|2020|matchday24|apollonsmyrnis|ofiheraklion"
        val englishFixture =
            "fixture:openfootball:en.2|2021|matchday30|barnsleyfc|cardiffcityfc"

        assertEquals(-5414043580544583115L, stableOpenFootballFixtureId(greekFixture))
        assertEquals(-5414043579561668037L, stableOpenFootballFixtureId(englishFixture))
        assertEquals(
            -5414043580544583115L,
            openFootballFixtureId(
                "openfootball:gr.1",
                2020,
                "Matchday 24",
                "Apollon Smyrnis",
                "OFI Heraklion",
                "2020-02-01",
            ),
        )
        assertNotEquals(
            stableOpenFootballFixtureId(greekFixture),
            stableOpenFootballFixtureId(englishFixture),
        )
        assertTrue(stableOpenFootballFixtureId(greekFixture) < Int.MIN_VALUE.toLong())
    }
}
