package neth.iecal.curbox.utils

import com.google.gson.Gson
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.AppBlockingType
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.data.models.AutoDndGroup
import neth.iecal.curbox.data.models.GatedSettingsField
import neth.iecal.curbox.data.models.GrayscaleGroup
import neth.iecal.curbox.data.models.KeywordBlocker
import neth.iecal.curbox.data.models.KeywordGroup
import neth.iecal.curbox.data.models.MindfulMessageConfig
import neth.iecal.curbox.data.models.ReelBlocker
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.data.models.SettingsChangeDelayConfig
import neth.iecal.curbox.data.models.TimeInterval
import neth.iecal.curbox.data.models.UiHiderConfig
import neth.iecal.curbox.data.models.upgradeLegacyConfig
import neth.iecal.curbox.hardcoded.allScripts

/**
 * Decides whether a proposed settings value keeps every restriction at least as strong as
 * the current one. The settings change delay applies "same or stricter" changes right away
 * and holds everything else for the countdown.
 *
 * The comparison is deliberately conservative: whenever a change cannot be proven to be
 * same or stricter (unparseable config, incomparable blocking types, unknown fields), it is
 * treated as a reduction and delayed. A restriction that is currently switched off imposes
 * nothing, so any change to it passes.
 */
object RestrictionComparator {

    private val gson = Gson()

    fun isSameOrStricter(field: GatedSettingsField, current: Settings, proposed: Settings): Boolean {
        return try {
            when (field) {
                GatedSettingsField.APP_GROUPS ->
                    appGroups(current.blockedAppGroups, proposed.blockedAppGroups)
                GatedSettingsField.AUTO_DND_GROUPS ->
                    autoDndGroups(current.autoDndGroups, proposed.autoDndGroups)
                GatedSettingsField.REEL_BLOCKER ->
                    reelBlocker(current.reelBlockerConfig, proposed.reelBlockerConfig)
                GatedSettingsField.KEYWORD_BLOCKER ->
                    keywordBlocker(current.keywordBlockerConfig, proposed.keywordBlockerConfig)
                GatedSettingsField.REEL_COUNTER ->
                    !current.isReelCounterOn || proposed.isReelCounterOn
                GatedSettingsField.GRAYSCALE_GROUPS ->
                    grayscaleGroups(current.grayscaleGroups, proposed.grayscaleGroups)
                GatedSettingsField.MINDFUL_MESSAGES ->
                    mindfulMessages(current.mindfulMessageConfig, proposed.mindfulMessageConfig)
                GatedSettingsField.UI_HIDER ->
                    uiHider(current.uiHiderConfig, proposed.uiHiderConfig)
                GatedSettingsField.APP_USAGE_TRACKING ->
                    !current.isAppUsageTrackingEnabled || proposed.isAppUsageTrackingEnabled
                GatedSettingsField.WEBSITE_USAGE_TRACKING ->
                    !current.isWebsiteUsageTrackingEnabled || proposed.isWebsiteUsageTrackingEnabled
                GatedSettingsField.CHANGE_DELAY ->
                    changeDelay(current.settingsChangeDelayConfig2, proposed.settingsChangeDelayConfig2)
            }
        } catch (e: Exception) {
            false
        }
    }

    fun changeDelay(old: SettingsChangeDelayConfig, new: SettingsChangeDelayConfig): Boolean {
        // Off or set to no wait means the timer holds nothing, so any change to it passes
        val timeOk = !old.isEnabled || old.delayMinutes <= 0 ||
            (new.isEnabled && new.delayMinutes >= old.delayMinutes)
        // Turning this off is itself a reduction, so it has to earn its own way past the gate
        val tamperGateOk = !old.requireTamperProtectionOff || new.requireTamperProtectionOff
        return timeOk && tamperGateOk
    }

    fun appGroups(old: List<AppGroup>, new: List<AppGroup>): Boolean {
        return old.filter { it.isActive }.all { o ->
            val n = new.find { it.id == o.id } ?: return@all false
            appGroup(o, n)
        }
    }

    private fun appGroup(o: AppGroup, n: AppGroup): Boolean {
        if (!n.isActive) return false
        if (!n.selectedPackages.containsAll(o.selectedPackages)) return false
        if (!warningConfig(o.warningScreenConfig, n.warningScreenConfig)) return false
        val oldConfig = o.config ?: return false
        val newConfig = n.config ?: return false
        return appTimeCoverageSameOrWider(oldConfig.schedule, newConfig.schedule) &&
            usageLimitSameOrLower(oldConfig.usage, newConfig.usage)
    }

    fun keywordBlocker(old: KeywordBlocker, new: KeywordBlocker): Boolean {
        if (!old.isActive) return true
        if (!new.isActive) return false
        if (old.blockAllExceptSupported && !new.blockAllExceptSupported) return false
        return old.keywordGroups.filter { it.isActive }.all { o ->
            val n = new.keywordGroups.find { it.id == o.id } ?: return@all false
            keywordGroup(o, n)
        }
    }

    private fun keywordGroup(o: KeywordGroup, n: KeywordGroup): Boolean {
        if (!n.isActive) return false
        if (!n.selectedKeywords.containsAll(o.selectedKeywords)) return false
        if (!warningConfig(o.warningScreenConfig, n.warningScreenConfig)) return false
        val oldConfig = o.config ?: return false
        val newConfig = n.config ?: return false
        return appTimeCoverageSameOrWider(oldConfig.schedule, newConfig.schedule) &&
            usageLimitSameOrLower(oldConfig.usage, newConfig.usage)
    }

    fun reelBlocker(old: ReelBlocker, new: ReelBlocker): Boolean {
        val o = old.upgradeLegacyConfig(gson)
        val n = new.upgradeLegacyConfig(gson)
        if (!o.isActive) return true
        if (!n.isActive) return false
        if (!o.excludedPackages.containsAll(n.excludedPackages)) return false
        if (!warningConfig(o.warningScreenConfig, n.warningScreenConfig)) return false
        val oldConfig = o.config ?: return false
        val newConfig = n.config ?: return false
        val oldUsage = if (oldConfig.usage.isDailyUniform) List(7) { oldConfig.usage.uniformLimit } else oldConfig.usage.dailyLimits.toList()
        val newUsage = if (newConfig.usage.isDailyUniform) List(7) { newConfig.usage.uniformLimit } else newConfig.usage.dailyLimits.toList()
        val oldCount = if (oldConfig.reelCount.isDailyUniform) List(7) { oldConfig.reelCount.uniformLimit } else oldConfig.reelCount.dailyLimits.toList()
        val newCount = if (newConfig.reelCount.isDailyUniform) List(7) { newConfig.reelCount.uniformLimit } else newConfig.reelCount.dailyLimits.toList()
        return timeCoverageSameOrWider(
            oldFor = { day -> if (oldConfig.schedule.isEveryday) oldConfig.schedule.everydayIntervals else oldConfig.schedule.dailyIntervals[day] ?: mutableListOf() },
            newFor = { day -> if (newConfig.schedule.isEveryday) newConfig.schedule.everydayIntervals else newConfig.schedule.dailyIntervals[day] ?: mutableListOf() }
        ) && newUsage.indices.all { newUsage[it] <= oldUsage[it] } &&
            newCount.indices.all { newCount[it] <= oldCount[it] }
    }

    fun grayscaleGroups(old: List<GrayscaleGroup>, new: List<GrayscaleGroup>): Boolean {
        return old.filter { it.isActive }.all { o ->
            val n = new.find { it.groupId == o.groupId } ?: return@all false
            n.isActive &&
                n.packages.containsAll(o.packages) &&
                appTimeCoverageSameOrWider(o.timeConfig, n.timeConfig)
        }
    }

    fun autoDndGroups(old: List<AutoDndGroup>, new: List<AutoDndGroup>): Boolean {
        return old.all { o ->
            val n = new.find { it.groupId == o.groupId } ?: return@all false
            (!o.autoTurnOnDnd || n.autoTurnOnDnd) &&
                appTimeCoverageSameOrWider(o.timeConfig, n.timeConfig)
        }
    }

    fun mindfulMessages(old: MindfulMessageConfig, new: MindfulMessageConfig): Boolean {
        if (!old.isActive) return true
        return new.isActive && new.selectedApps.containsAll(old.selectedApps)
    }

    fun uiHider(old: UiHiderConfig, new: UiHiderConfig): Boolean {
        if (!old.isActive) return true
        if (!new.isActive) return false
        return old.allScripts().filter { it.isEnabled }.all { o ->
            val n = new.allScripts().find { it.id == o.id } ?: return@all false
            n.isEnabled && n.packageName == o.packageName && n.source == o.source
        }
    }

    private fun blockingSetting(type: AppBlockingType, oldJson: String, newJson: String): Boolean {
        if (oldJson == newJson) return true
        return when (type) {
            AppBlockingType.Usage -> {
                val o = parse<AppUsageConfig>(oldJson) ?: return false
                val n = parse<AppUsageConfig>(newJson) ?: return false
                usageLimitSameOrLower(o, n)
            }
            AppBlockingType.Timed -> {
                val o = parse<AppTimeConfig>(oldJson) ?: return false
                val n = parse<AppTimeConfig>(newJson) ?: return false
                appTimeCoverageSameOrWider(o, n)
            }
            // OnOpen has no comparable knobs; a changed config is treated as a reduction
            AppBlockingType.OnOpen -> false
        }
    }

    /**
     * The intervals are the times when the restriction is in force, so stricter means the new
     * intervals cover at least every minute the old ones did, on every day of the week.
     */
    private fun appTimeCoverageSameOrWider(old: AppTimeConfig, new: AppTimeConfig): Boolean {
        return timeCoverageSameOrWider(
            oldFor = { day -> if (old.isEveryday) old.everydayIntervals else old.dailyIntervals[day] ?: mutableListOf() },
            newFor = { day -> if (new.isEveryday) new.everydayIntervals else new.dailyIntervals[day] ?: mutableListOf() }
        )
    }

    private fun usageLimitSameOrLower(old: AppUsageConfig, new: AppUsageConfig): Boolean {
        val oldLimits =
            if (old.isDailyUniform) List(7) { old.uniformLimit } else old.dailyLimits.toList()
        val newLimits =
            if (new.isDailyUniform) List(7) { new.uniformLimit } else new.dailyLimits.toList()
        return newLimits.indices.all { newLimits[it] <= oldLimits[it] }
    }

    private fun timeCoverageSameOrWider(
        oldFor: (Int) -> List<TimeInterval>,
        newFor: (Int) -> List<TimeInterval>
    ): Boolean {
        // Day keys are checked over 0..7 so both 0 based and Calendar style maps are covered
        return (0..7).all { day -> covers(newFor(day), oldFor(day)) }
    }

    /** True when the union of [covering] contains every minute of every interval in [covered]. */
    private fun covers(covering: List<TimeInterval>, covered: List<TimeInterval>): Boolean {
        val merged = mergeRanges(covering.map { it.toMinuteRange() })
        return covered.map { it.toMinuteRange() }
            .filter { it.first < it.second }
            .all { (start, end) -> merged.any { it.first <= start && end <= it.second } }
    }

    private fun TimeInterval.toMinuteRange(): Pair<Int, Int> =
        Pair(startHour * 60 + startMinute, endHour * 60 + endMinute)

    private fun mergeRanges(ranges: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val sorted = ranges.filter { it.first < it.second }.sortedBy { it.first }
        val merged = mutableListOf<Pair<Int, Int>>()
        for (range in sorted) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.second) {
                merged[merged.size - 1] = Pair(last.first, maxOf(last.second, range.second))
            } else {
                merged.add(range)
            }
        }
        return merged
    }

    fun warningConfig(o: AppBlockerWarningScreenConfig, n: AppBlockerWarningScreenConfig): Boolean {
        // Message and typing sentence wording never change how strong the block is
        if (o.copy(message = listOf(), typingSentence = "") == n.copy(message = listOf(), typingSentence = "")) return true

        val onEachOpenOk = !o.isOnOpenConfig || n.isOnOpenConfig
        val cooldownOk = n.isOnOpenConfig || n.timeInterval <= o.timeInterval
        val dynamicIntervalOk = n.isOnOpenConfig ||
            !n.isDynamicIntervalSettingAllowed ||
            o.isDynamicIntervalSettingAllowed
        val proceedDisabledOk = !o.isProceedDisabled || n.isProceedDisabled
        val dialogHiddenOk = !o.isWarningDialogHidden || n.isWarningDialogHidden
        val proceedDelayOk = n.proceedDelayInSecs >= o.proceedDelayInSecs
        val vibrateOk = !o.vibrateAndIncBrightness || n.vibrateAndIncBrightness
        val proceedLimitOk = when {
            o.proceedLimitEnabled && !n.proceedLimitEnabled -> false
            o.proceedLimitEnabled && n.proceedLimitEnabled ->
                n.allowedProceeds <= o.allowedProceeds && n.proceedsTimeWindowMn >= o.proceedsTimeWindowMn
            else -> true
        }
        // QR and NFC share the same rule: a key/tag is a new way to unlock, so keys may only be
        // kept or removed, and a kept key may not unlock for longer than before.
        val qrOk = unlockKeysOk(o.isQrUnlockRequirementEnabled, n.isQrUnlockRequirementEnabled, o.qrKeys, n.qrKeys)
        val nfcOk = unlockKeysOk(o.isNfcUnlockRequirementEnabled, n.isNfcUnlockRequirementEnabled, o.nfcKeys, n.nfcKeys)
        val typingOk = !o.isTypingRequirementEnabled || n.isTypingRequirementEnabled
        val intentOk = !o.isIntentRequirementEnabled || n.isIntentRequirementEnabled
        val adaptiveMathOk = !o.isAdaptiveMathRequirementEnabled ||
            n.isAdaptiveMathRequirementEnabled
        val adaptiveMathQuestionCountOk = when {
            !o.isAdaptiveMathRequirementEnabled || !n.isAdaptiveMathRequirementEnabled -> true
            else -> n.adaptiveMathQuestionCount.coerceAtLeast(1) >=
                o.adaptiveMathQuestionCount.coerceAtLeast(1)
        }
        val adaptiveMathStartingLevelOk = when {
            !o.isAdaptiveMathRequirementEnabled || !n.isAdaptiveMathRequirementEnabled -> true
            else -> n.adaptiveMathStartingLevel.coerceIn(1, 10) >=
                o.adaptiveMathStartingLevel.coerceIn(1, 10)
        }
        val focusGoalOk = when {
            o.isFocusGoalRequirementEnabled && !n.isFocusGoalRequirementEnabled -> false
            o.isFocusGoalRequirementEnabled && n.isFocusGoalRequirementEnabled ->
                n.focusGoalGroupId == o.focusGoalGroupId &&
                    n.focusGoalRequiredMinutes.coerceAtLeast(1) >=
                    o.focusGoalRequiredMinutes.coerceAtLeast(1)
            else -> true
        }
        val intentMinLengthOk = when {
            !o.isIntentRequirementEnabled || !n.isIntentRequirementEnabled -> true
            else -> n.minIntentLength.coerceAtLeast(1) >= o.minIntentLength.coerceAtLeast(1)
        }

        return onEachOpenOk && cooldownOk && dynamicIntervalOk &&
            proceedDisabledOk && dialogHiddenOk &&
            proceedDelayOk && vibrateOk && proceedLimitOk && qrOk && nfcOk && typingOk && intentOk &&
            adaptiveMathOk && adaptiveMathQuestionCountOk && adaptiveMathStartingLevelOk &&
            focusGoalOk && intentMinLengthOk
    }

    /**
     * Shared "unlock keys may only get stricter" rule for QR and NFC. Disabling the requirement or
     * adding a new key/tag weakens the block; a kept key may not unlock for longer than before, and
     * dynamic timing (-1) is only same-or-stricter when it is unchanged.
     */
    private fun unlockKeysOk(
        oldEnabled: Boolean,
        newEnabled: Boolean,
        oldKeys: Map<String, Long>,
        newKeys: Map<String, Long>
    ): Boolean = when {
        oldEnabled && !newEnabled -> false
        oldEnabled && newEnabled -> newKeys.all { (key, duration) ->
            val oldDuration = oldKeys[key] ?: return@all false
            if (oldDuration == -1L || duration == -1L) duration == oldDuration
            else duration <= oldDuration
        }
        else -> true
    }

    private inline fun <reified T> parse(json: String): T? =
        runCatching { gson.fromJson(json, T::class.java) }.getOrNull()
}
