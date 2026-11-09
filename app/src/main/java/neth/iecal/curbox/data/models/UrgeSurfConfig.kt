package neth.iecal.curbox.data.models

data class UrgeSurfConfig(
    /** Shows the "I'm struggling" button on every block screen. */
    val isButtonEnabled: Boolean = true,
    /** Offers help when the user unlocks blocked content several times in a row. */
    val isAutoOfferEnabled: Boolean = true
) {
    companion object {
        const val AUTO_OFFER_UNLOCK_COUNT = 3
    }
}
