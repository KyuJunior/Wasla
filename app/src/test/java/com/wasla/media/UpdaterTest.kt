package com.wasla.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun comparesVersionsNumerically() {
        assertTrue(Updater.isNewer("1.2", "1.1"))
        assertTrue(Updater.isNewer("1.10", "1.9"))
        assertTrue(Updater.isNewer("2", "1.9.9"))
        assertTrue(Updater.isNewer("1.1.1", "1.1"))
        assertFalse(Updater.isNewer("1.1", "1.1"))
        assertFalse(Updater.isNewer("1.1.0", "1.1"))
        assertFalse(Updater.isNewer("1.0", "1.1"))
        assertFalse(Updater.isNewer("1.2-beta", "1.2"))
    }
}
