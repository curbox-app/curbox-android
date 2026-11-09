package neth.iecal.curbox.data.models

data class UrgeSurfConfig(
    /** Shows the "I'm struggling" button on every block screen. */
    val isButtonEnabled: Boolean = true,
    /** Offers help when the user is blocked several times in a short window. */
    val isAutoOfferEnabled: Boolean = true
) {
    companion object {
        const val AUTO_OFFER_BLOCK_COUNT = 3
        const val AUTO_OFFER_WINDOW_MS = 5 * 60_000L
    }
}
