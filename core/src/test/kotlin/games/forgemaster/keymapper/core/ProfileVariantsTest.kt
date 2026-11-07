package games.forgemaster.keymapper.core

import games.forgemaster.keymapper.core.engine.Screen
import games.forgemaster.keymapper.core.model.Profile
import games.forgemaster.keymapper.core.model.ProfileVariants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ProfileVariantsTest {
    @Test
    fun aspectKeys() {
        assertEquals("16x9", ProfileVariants.aspectKey(Screen(1920, 1080, 1, 1080, 2400)))
        assertEquals("20x9", ProfileVariants.aspectKey(Screen(2400, 1080, 1)))
        assertEquals("9x16", ProfileVariants.aspectKey(Screen(1080, 1920)))
        assertEquals("20x9", ProfileVariants.aspectKey(Screen(2560, 1152)))
    }

    @Test
    fun variantReplacesItsBaseOnlyOnItsAspect() {
        val base = Profile("wild-rift", "Wild Rift")
        val wide = Profile("wild-rift@16x9", "Wild Rift 16:9")
        val profiles = listOf(base, wide).associateBy { it.id }
        assertSame(wide, ProfileVariants.resolve(base, profiles, Screen(1920, 1080, 1)))
        assertSame(base, ProfileVariants.resolve(base, profiles, Screen(2400, 1080, 1)))
        assertSame(base, ProfileVariants.resolve(base, mapOf(base.id to base), Screen(1920, 1080)))
        assertNull(ProfileVariants.resolve(null, profiles, Screen(1920, 1080)))
    }
}
