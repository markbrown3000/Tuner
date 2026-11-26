package com.titanejstudios.backupguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CleanNameTest {
    @Test
    fun namesThatShouldMatch() {
        assertEquals(BackupGuide.cleanName("BBC 1"), BackupGuide.cleanName("BBC One HD"))
        assertEquals(BackupGuide.cleanName("BBC One"), BackupGuide.cleanName("UK: BBC One FHD (1080p)"))
        assertEquals(BackupGuide.cleanName("Sky Sports Main Event"), BackupGuide.cleanName("UK | Sky Sports Main Event HD"))
        assertEquals(BackupGuide.cleanName("Channel 4"), BackupGuide.cleanName("Channel Four"))
        assertEquals("nowsports1", BackupGuide.cleanName("HK: Now Sports 1 HD"))
    }

    @Test
    fun namesThatShouldNot() {
        assertNotEquals(BackupGuide.cleanName("ITV1"), BackupGuide.cleanName("ITV1 +1"))
        assertNotEquals(BackupGuide.cleanName("BBC One"), BackupGuide.cleanName("BBC Two"))
    }
}
