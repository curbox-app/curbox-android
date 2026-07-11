package neth.iecal.curbox.data.models


data class AppBlockerWarningScreenConfig(
    val message: String = "You can setup a custom message to appear here!",
    val timeInterval: Int = 120000, // default cooldown period
    val isDynamicIntervalSettingAllowed: Boolean = false,
    val isProceedDisabled: Boolean = false,
    val isWarningDialogHidden: Boolean = false, // perform back/home action directly without showing warning screen
    val proceedDelayInSecs: Int = 15,
    val vibrateAndIncBrightness: Boolean = false,
    val proceedLimitEnabled: Boolean = false,
    val allowedProceeds: Int = 3,
    val proceedsTimeWindowMn: Int = 60,
    val isQrUnlockRequirementEnabled: Boolean = false,
    val qrKeys: Map<String,Long> = mapOf(), // qr code content -> Duration of unlock (-1 if dynamic timing)
    val isTypingRequirementEnabled: Boolean = false,
    val typingSentence: String = "",
    val isIntentRequirementEnabled: Boolean = false,
    val minIntentLength: Int = 1,
    var isOnOpenConfig: Boolean = false,
)
