package com.titanejstudios.tuner

import com.titanejstudios.tuner.data.Fuzzy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyTest {
    @Test
    fun forgivesTyposButNotNonsense() {
        assertTrue(Fuzzy.matches("strictley", "Strictly Come Dancing"))
        assertTrue(Fuzzy.matches("coronation st", "Coronation Street"))
        assertTrue(Fuzzy.matches("lord rings", "The Lord of the Rings"))
        assertFalse(Fuzzy.matches("snooker", "Coronation Street"))
    }
}
