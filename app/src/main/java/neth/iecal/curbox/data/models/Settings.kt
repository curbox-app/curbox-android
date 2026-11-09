package neth.iecal.curbox.data.models

data class Settings(
    val externalUnlockConditions: List<ExternalUnlockCondition> = emptyList(),
    val blockedAppGroups: List<AppGroup> = listOf(),
    val manualFocusGroups: List<ManualFocusGroup> = listOf(),
    val autoDndGroups: List<AutoDndGroup> = listOf(),
    /**
     * Stores info about active manual focus mode.
     * Format Pair<GroupId?, system ms when it ends>.
     * Set group id as null when no active focus mode is running
     */
    val activeManualFocusGroupId: Pair<String?, Long> = Pair(null, 0),
    val activePomodoroState: PomodoroState = PomodoroState(),

    val reelBlockerConfig: ReelBlocker = ReelBlocker(),
    val keywordBlockerConfig: KeywordBlocker = KeywordBlocker(),
    val isReelCounterOn: Boolean = true,
    val grayscaleGroups: List<GrayscaleGroup> = listOf(),
    val usageBackgroundArt: String = "",
    val usageTrackerIgnoredApps: List<String> = listOf(),
    val isAppUsageTrackingEnabled: Boolean = true,
    val isWebsiteUsageTrackingEnabled: Boolean = true,
    val mindfulMessageConfig: MindfulMessageConfig = MindfulMessageConfig(),
    val sipStretchConfig: SipStretchConfig = SipStretchConfig(),
    /** System ms when the running Sip & Stretch break ends. 0 when no break is running. */
    val sipStretchBreakEndsAtMs: Long = 0L,
    val uiHiderConfig: UiHiderConfig = UiHiderConfig(),
    val reelCounterOverlayConfig: ReelCounterOverlayConfig = ReelCounterOverlayConfig(),
    val nextWebsiteRecheckTime: Long = 0L,
    val serviceProtectionConfig: ServiceProtectionConfig = ServiceProtectionConfig(),
    val antiUninstallConfig2: AntiUninstallConfig = AntiUninstallConfig(),
    val settingsChangeDelayConfig2: SettingsChangeDelayConfig = SettingsChangeDelayConfig()
)
