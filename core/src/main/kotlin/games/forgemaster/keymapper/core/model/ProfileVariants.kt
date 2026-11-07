package games.forgemaster.keymapper.core.model

import games.forgemaster.keymapper.core.engine.Screen

/**
 * Screen-shape variants. A profile whose id is `<id>@<W>x<H>` is used instead of profile `<id>`
 * while the logical screen has the aspect ratio W:H (reduced, as the screen is oriented): for
 * example `wild-rift@16x9` replaces `wild-rift` while a 2400x1080 phone runs `wm size 1080x1920`
 * in landscape. Games stay assigned to the base profile; games lay out their HUD differently per
 * aspect ratio, which fractions of the screen alone cannot follow.
 */
object ProfileVariants {
    /** "16x9" for 1920x1080, "20x9" for 2400x1080, "9x16" for 1080x1920. */
    fun aspectKey(screen: Screen): String {
        val w = screen.width
        val h = screen.height
        if (w <= 0 || h <= 0) return "0x0"
        val g = gcd(w, h)
        return "${w / g}x${h / g}"
    }

    fun variantId(baseId: String, screen: Screen): String = "$baseId@${aspectKey(screen)}"

    /** The variant of [base] for [screen] if [profiles] has one, else [base]. */
    fun resolve(base: Profile?, profiles: Map<String, Profile>, screen: Screen): Profile? =
        base?.let { profiles[variantId(it.id, screen)] ?: it }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
