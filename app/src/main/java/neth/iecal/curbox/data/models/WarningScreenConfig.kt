package neth.iecal.curbox.data.models

//You can setup a custom message to appear here!
data class AppBlockerWarningScreenConfig(
    val message: List<String> = listOf("Message 1", "Message 2"),
    val timeInterval: Long = 120000L, // default cooldown period
    val isDynamicIntervalSettingAllowed: Boolean = false,
    val isProceedDisabled: Boolean = false,
    val isWarningDialogHidden: Boolean = false, // perform back/home action directly without showing warning screen
    val proceedDelayInSecs: Int = 15,
    val vibrateAndIncBrightness: Boolean = false,
    val proceedLimitEnabled: Boolean = false,
    val allowedProceeds: Int = 3,
    val proceedsTimeWindowMn: Long = 60L,
    val isQrUnlockRequirementEnabled: Boolean = false,
    val qrKeys: Map<String,Long> = mapOf(), // qr code content -> Duration of unlock (-1 if dynamic timing)
    val isNfcUnlockRequirementEnabled: Boolean = false,
    val nfcKeys: Map<String,Long> = mapOf(), // nfc tag key (written UUID or UID) -> Duration of unlock (-1 if dynamic timing)
    val isTypingRequirementEnabled: Boolean = false,
    val typingSentence: String = "",
    val isIntentRequirementEnabled: Boolean = false,
    val minIntentLength: Int = 1,
    val isAdaptiveMathRequirementEnabled: Boolean = false,
    val adaptiveMathQuestionCount: Int = 3,
    val adaptiveMathStartingLevel: Int = 3,
    val isFocusGoalRequirementEnabled: Boolean = false,
    val focusGoalGroupId: String = "",
    val focusGoalRequiredMinutes: Int = 60,
    /** For app groups, grant access only for the current selected-app session. */
    val isOnOpenConfig: Boolean = false,
)
