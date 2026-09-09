package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared

import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.ManualFocusGroup
import neth.iecal.curbox.databinding.FragmentWarningConfigBinding

internal class WarningConfigFormController(
    private val fragment: Fragment,
    private val binding: FragmentWarningConfigBinding,
    private val onEachOpenChanged: () -> Unit
) {
    private var supportsOnEachOpen = false
    private val warningChallengeOptions by lazy {
        createWarningChallengeOptions(fragment.requireContext())
    }
    private val warningEffortOptions by lazy {
        createWarningEffortOptions(fragment.requireContext())
    }
    private val warningNoEffortOptions by lazy {
        createWarningNoEffortOptions(fragment.requireContext())
    }
    private var focusGroupOptions: List<FocusGroupUnlockOption> = emptyList()
    private var selectedFocusGroupId = ""

    fun bind(
        config: AppBlockerWarningScreenConfig,
        isNew: Boolean,
        supportsOnEachOpen: Boolean
    ) {
        this.supportsOnEachOpen = supportsOnEachOpen
        val isOnEachOpenEnabled = supportsOnEachOpen && config.isOnOpenConfig
        binding.switchOnEachOpen.isVisible = supportsOnEachOpen
        binding.switchOnEachOpen.isChecked = isOnEachOpenEnabled
        binding.unlockChallengeDropdown.setAdapter(
            WarningUnlockOptionAdapter(fragment.requireContext(), warningChallengeOptions)
        )

        if (isNew) {
            hideUnlockConfiguration()
        } else {
            val selection = WarningUnlockSelectionMapper.fromConfig(
                config,
                isOnEachOpenEnabled
            )
            binding.unlockChallengeDropdown.setText(
                warningChallengeOptions[selection.challengeIndex].title,
                false
            )
            updateSecondaryDropdown(selection.challengeIndex)
            if (selection.secondaryIndex != -1) {
                binding.secondaryBehaviorDropdown.setText(
                    secondaryOptions(selection.challengeIndex)[selection.secondaryIndex].title,
                    false
                )
            }
            updateUiVisibility(
                selection.challengeIndex,
                selection.secondaryIndex,
                animate = isOnEachOpenEnabled
            )
        }

        binding.typingSentenceEdit.setText(config.typingSentence)
        val intentMinLength = config.minIntentLength.coerceAtLeast(1)
        binding.intentMinLengthSlider.value = intentMinLength.toFloat().coerceAtMost(100f)
        updateIntentMinLengthInput(intentMinLength.toLong())

        val mathQuestionCount = config.adaptiveMathQuestionCount.coerceAtLeast(MIN_MATH_QUESTIONS)
        binding.mathQuestionCountSlider.value = mathQuestionCount.toFloat()
            .coerceAtMost(MAX_MATH_QUESTIONS.toFloat())
        updateMathQuestionCountInput(mathQuestionCount.toLong())
        val mathStartingLevel = config.adaptiveMathStartingLevel.coerceAtLeast(MIN_MATH_LEVEL)
        binding.mathStartingLevelSlider.value = mathStartingLevel.toFloat()
            .coerceAtMost(MAX_MATH_LEVEL.toFloat())
        updateMathStartingLevelInput(mathStartingLevel.toLong())

        val focusGoalMinutes = config.focusGoalRequiredMinutes.coerceAtLeast(MIN_FOCUS_MINUTES)
        binding.focusGoalMinutesSlider.value = focusGoalMinutes.toFloat()
            .coerceAtMost(MAX_FOCUS_MINUTES.toFloat())
        updateFocusGoalMinutesInput(focusGoalMinutes.toLong())
        selectedFocusGroupId = config.focusGoalGroupId
        binding.focusGoalRequirementSwitch.isChecked = config.isFocusGoalRequirementEnabled
        binding.focusGoalSetupContainer.isVisible = config.isFocusGoalRequirementEnabled

        val fixedTimeMinutes = (config.timeInterval / 60_000L).coerceAtLeast(1L)
        binding.fixedTimeSlider.value = fixedTimeMinutes.toFloat().coerceAtMost(120f)
        updateFixedTimeInput(fixedTimeMinutes)

        val proceedDelay = config.proceedDelayInSecs.coerceAtLeast(0)
        binding.proceedDelaySlider.value = proceedDelay.toFloat().coerceAtMost(60f)
        updateProceedDelayInput(proceedDelay.toLong())

        binding.proceedLimitSwitch.isChecked = config.proceedLimitEnabled
        binding.proceedLimitContainer.isVisible = config.proceedLimitEnabled

        val allowedProceeds = config.allowedProceeds.coerceAtLeast(1)
        binding.allowedProceedsSlider.value = allowedProceeds.toFloat().coerceAtMost(20f)
        updateAllowedProceedsInput(allowedProceeds.toLong())

        val totalMinutes = config.proceedsTimeWindowMn
        val (initialUnitIndex, initialInputValue) = when {
            totalMinutes > 0 && totalMinutes % MINUTES_PER_DAY == 0L ->
                DAYS_INDEX to (totalMinutes / MINUTES_PER_DAY)
            totalMinutes > 0 && totalMinutes % MINUTES_PER_HOUR == 0L ->
                HOURS_INDEX to (totalMinutes / MINUTES_PER_HOUR)
            else -> MINUTES_INDEX to totalMinutes.coerceAtLeast(1L)
        }
        binding.proceedWindowUnitBtn.text = unitOptions()[initialUnitIndex]
        updateProceedWindowSliderBounds(initialUnitIndex)
        binding.proceedWindowSlider.value = initialInputValue.toFloat().coerceIn(
            binding.proceedWindowSlider.valueFrom,
            binding.proceedWindowSlider.valueTo
        )
        updateProceedWindowInput(initialInputValue)

        binding.warningMsgEdit.setText(config.message.joinToString(", "))
        binding.switchVibrateBrightness.isChecked = config.vibrateAndIncBrightness
    }

    fun setupListeners(onSave: () -> Unit) {
        binding.unlockChallengeDropdown.setOnItemClickListener { _, _, position, _ ->
            updateSecondaryDropdown(position)
            val isOnEachOpenEnabled = isOnEachOpenEnabled()
            if (!(isOnEachOpenEnabled &&
                    position == WarningUnlockSelectionMapper.NO_EFFORT_INDEX)) {
                binding.secondaryBehaviorDropdown.setText("", false)
            }
            val secondaryIndex =
                if (isOnEachOpenEnabled &&
                    position == WarningUnlockSelectionMapper.NO_EFFORT_INDEX) {
                    WarningUnlockSelectionMapper.FIXED_TIME_INDEX
                } else {
                    -1
                }
            updateUiVisibility(position, secondaryIndex, animate = true)
        }

        binding.secondaryBehaviorDropdown.setOnItemClickListener { _, _, position, _ ->
            updateUiVisibility(selectedChallengeIndex(), position, animate = true)
        }

        setupNumericInput(
            binding.fixedTimeSlider,
            binding.fixedTimeInput,
            binding.fixedTimeInputLayout,
            maximumValue = { Long.MAX_VALUE / 60_000L },
            updateInput = ::updateFixedTimeInput
        )
        setupNumericInput(
            binding.proceedDelaySlider,
            binding.proceedDelayInput,
            binding.proceedDelayInputLayout,
            updateInput = ::updateProceedDelayInput
        )
        binding.proceedLimitSwitch.setOnCheckedChangeListener { _, isChecked ->
            binding.proceedLimitContainer.isVisible = isChecked
        }
        binding.switchOnEachOpen.setOnCheckedChangeListener { _, isChecked ->
            val challengeIndex = selectedChallengeIndex()
            if (isChecked && challengeIndex == WarningUnlockSelectionMapper.NO_EFFORT_INDEX) {
                binding.secondaryBehaviorDropdown.setText(
                    warningNoEffortOptions[WarningUnlockSelectionMapper.FIXED_TIME_INDEX].title,
                    false
                )
            }
            updateSecondaryDropdown(challengeIndex)
            updateUiVisibility(
                challengeIndex,
                selectedSecondaryIndex(challengeIndex),
                animate = true
            )
            onEachOpenChanged()
        }
        setupNumericInput(
            binding.allowedProceedsSlider,
            binding.allowedProceedsInput,
            binding.allowedProceedsInputLayout,
            updateInput = ::updateAllowedProceedsInput
        )
        setupNumericInput(
            binding.proceedWindowSlider,
            binding.proceedWindowInput,
            binding.proceedWindowInputLayout,
            maximumValue = {
                Long.MAX_VALUE / 60_000L / minutesForUnit(selectedUnitIndex())
            },
            updateInput = ::updateProceedWindowInput
        )
        binding.proceedWindowUnitBtn.setOnClickListener { button ->
            showProceedWindowUnitMenu(button)
        }
        setupNumericInput(
            binding.intentMinLengthSlider,
            binding.intentMinLengthInput,
            binding.intentMinLengthInputLayout,
            updateInput = ::updateIntentMinLengthInput
        )
        setupNumericInput(
            binding.mathQuestionCountSlider,
            binding.mathQuestionCountInput,
            binding.mathQuestionCountInputLayout,
            updateInput = ::updateMathQuestionCountInput
        )
        setupNumericInput(
            binding.mathStartingLevelSlider,
            binding.mathStartingLevelInput,
            binding.mathStartingLevelInputLayout,
            updateInput = ::updateMathStartingLevelInput
        )
        setupNumericInput(
            binding.focusGoalMinutesSlider,
            binding.focusGoalMinutesInput,
            binding.focusGoalMinutesInputLayout,
            updateInput = ::updateFocusGoalMinutesInput
        )
        binding.focusGoalGroupDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedFocusGroupId = focusGroupOptions[position].groupId
            binding.focusGoalGroupLayout.error = null
        }
        binding.focusGoalRequirementSwitch.setOnCheckedChangeListener { _, isChecked ->
            binding.focusGoalSetupContainer.isVisible = isChecked
            if (!isChecked) {
                binding.focusGoalGroupLayout.error = null
            }
        }
        binding.advancedSettingsHeader.setOnClickListener {
            val isCurrentlyVisible = binding.advancedSettingsContent.isVisible
            TransitionManager.beginDelayedTransition(
                binding.mainContentContainer,
                AutoTransition()
            )
            binding.advancedSettingsContent.isVisible = !isCurrentlyVisible
            binding.advancedSettingsArrow.animate()
                .rotation(if (isCurrentlyVisible) 0f else 90f)
                .start()
        }
        binding.saveconfigs.setOnClickListener { onSave() }
    }

    fun createConfig(
        qrKeys: Map<String, Long>,
        nfcKeys: Map<String, Long>
    ): AppBlockerWarningScreenConfig {
        val flags = WarningUnlockSelectionMapper.toFlags(
            selectedChallengeIndex(),
            selectedSecondaryIndex(selectedChallengeIndex())
        )
        return AppBlockerWarningScreenConfig(
            message = binding.warningMsgEdit.text.toString()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() },

            timeInterval = numericInputValue(binding.fixedTimeInput) * 60_000L,
            isDynamicIntervalSettingAllowed = flags.isDynamicIntervalSettingAllowed,
            isProceedDisabled = flags.isProceedDisabled,
            isWarningDialogHidden = false,
            isQrUnlockRequirementEnabled = flags.isQrUnlockRequirementEnabled,
            qrKeys = if (flags.isQrUnlockRequirementEnabled) qrKeys else emptyMap(),
            isNfcUnlockRequirementEnabled = flags.isNfcUnlockRequirementEnabled,
            nfcKeys = if (flags.isNfcUnlockRequirementEnabled) nfcKeys else emptyMap(),
            isTypingRequirementEnabled = flags.isTypingRequirementEnabled,
            typingSentence = binding.typingSentenceEdit.text.toString(),
            isIntentRequirementEnabled = flags.isIntentRequirementEnabled,
            minIntentLength = numericInputValue(binding.intentMinLengthInput).toInt(),
            isAdaptiveMathRequirementEnabled = flags.isAdaptiveMathRequirementEnabled,
            adaptiveMathQuestionCount = numericInputValue(binding.mathQuestionCountInput).toInt(),
            adaptiveMathStartingLevel = numericInputValue(binding.mathStartingLevelInput).toInt(),
            isFocusGoalRequirementEnabled = binding.focusGoalRequirementSwitch.isChecked,
            focusGoalGroupId = if (binding.focusGoalRequirementSwitch.isChecked) {
                selectedFocusGroupId
            } else {
                ""
            },
            focusGoalRequiredMinutes = numericInputValue(binding.focusGoalMinutesInput).toInt(),
            proceedDelayInSecs = numericInputValue(binding.proceedDelayInput).toInt(),
            vibrateAndIncBrightness = binding.switchVibrateBrightness.isChecked,
            proceedLimitEnabled = binding.proceedLimitSwitch.isChecked,
            allowedProceeds = numericInputValue(binding.allowedProceedsInput).toInt(),
            proceedsTimeWindowMn = selectedProceedWindowMinutes(),
            isOnOpenConfig = isOnEachOpenEnabled()
        )
    }

    fun isOnEachOpenEnabled(): Boolean {
        return supportsOnEachOpen && binding.switchOnEachOpen.isChecked
    }

    fun bindFocusGroups(groups: List<ManualFocusGroup>) {
        focusGroupOptions = groups.map { FocusGroupUnlockOption(it.groupId, it.groupName) }
        binding.focusGoalGroupDropdown.setAdapter(
            android.widget.ArrayAdapter(
                fragment.requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                focusGroupOptions
            )
        )
        val selectedGroup = focusGroupOptions.firstOrNull {
            it.groupId == selectedFocusGroupId
        }
        binding.focusGoalGroupDropdown.setText(selectedGroup?.name.orEmpty(), false)
    }

    fun validate(): Boolean {
        if (!numericInputs().all { validateNumericInput(it) }) {
            return false
        }
        if (binding.focusGoalRequirementSwitch.isChecked &&
            focusGroupOptions.none { it.groupId == selectedFocusGroupId }
        ) {
            binding.focusGoalGroupLayout.error = fragment.getString(
                R.string.warning_focus_goal_group_required
            )
            return false
        }
        return true
    }

    private fun setupNumericInput(
        slider: Slider,
        input: TextInputEditText,
        inputLayout: TextInputLayout,
        maximumValue: () -> Long = { Int.MAX_VALUE.toLong() },
        updateInput: (Long) -> Unit
    ) {
        slider.addOnChangeListener { _, value, _ ->
            updateInput(value.toLong())
        }
        input.doAfterTextChanged { editable ->
            val value = editable?.toString()?.toLongOrNull()
            if (value == null) {
                inputLayout.error = null
                return@doAfterTextChanged
            }
            if (value < slider.valueFrom.toLong()) {
                showNumericInputMinimumError(slider, inputLayout)
                return@doAfterTextChanged
            }
            if (value > maximumValue()) {
                showNumericInputTooLargeError(inputLayout)
                return@doAfterTextChanged
            }
            inputLayout.error = null
            if (value <= slider.valueTo.toLong() && slider.value.toLong() != value) {
                slider.value = value.toFloat()
            }
        }
        input.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && !isNumericInputValid(slider, input, maximumValue())) {
                inputLayout.error = null
                updateInput(slider.value.toLong())
            }
        }
    }

    private fun numericInputs(): List<NumericInput> {
        return listOf(
            NumericInput(
                binding.fixedTimeSlider,
                binding.fixedTimeInput,
                binding.fixedTimeInputLayout,
                Long.MAX_VALUE / 60_000L
            ),
            NumericInput(
                binding.proceedDelaySlider,
                binding.proceedDelayInput,
                binding.proceedDelayInputLayout
            ),
            NumericInput(
                binding.allowedProceedsSlider,
                binding.allowedProceedsInput,
                binding.allowedProceedsInputLayout
            ),
            NumericInput(
                binding.proceedWindowSlider,
                binding.proceedWindowInput,
                binding.proceedWindowInputLayout,
                Long.MAX_VALUE / 60_000L / minutesForUnit(selectedUnitIndex())
            ),
            NumericInput(
                binding.intentMinLengthSlider,
                binding.intentMinLengthInput,
                binding.intentMinLengthInputLayout
            ),
            NumericInput(
                binding.mathQuestionCountSlider,
                binding.mathQuestionCountInput,
                binding.mathQuestionCountInputLayout
            ),
            NumericInput(
                binding.mathStartingLevelSlider,
                binding.mathStartingLevelInput,
                binding.mathStartingLevelInputLayout
            ),
            NumericInput(
                binding.focusGoalMinutesSlider,
                binding.focusGoalMinutesInput,
                binding.focusGoalMinutesInputLayout
            )
        )
    }

    private fun validateNumericInput(numericInput: NumericInput): Boolean {
        if (isNumericInputValid(
                numericInput.slider,
                numericInput.input,
                numericInput.maximumValue
            )
        ) {
            numericInput.inputLayout.error = null
            return true
        }
        val value = numericInput.input.text?.toString()?.toLongOrNull()
        if (value != null && value > numericInput.maximumValue) {
            showNumericInputTooLargeError(numericInput.inputLayout)
        } else {
            showNumericInputMinimumError(numericInput.slider, numericInput.inputLayout)
        }
        numericInput.input.requestFocus()
        return false
    }

    private fun isNumericInputValid(
        slider: Slider,
        input: TextInputEditText,
        maximumValue: Long
    ): Boolean {
        val value = input.text?.toString()?.toLongOrNull() ?: return false
        return value >= slider.valueFrom.toLong() && value <= maximumValue
    }

    private fun showNumericInputMinimumError(slider: Slider, inputLayout: TextInputLayout) {
        inputLayout.error = fragment.getString(
            R.string.warning_numeric_value_minimum,
            slider.valueFrom.toInt()
        )
    }

    private fun showNumericInputTooLargeError(inputLayout: TextInputLayout) {
        inputLayout.error = fragment.getString(R.string.warning_numeric_value_too_large)
    }

    private fun hideUnlockConfiguration() {
        binding.secondaryBehaviorLayout.isVisible = false
        binding.timingContainer.isVisible = false
        binding.proceedDelayContainer.isVisible = false
        binding.qrSetupContainer.isVisible = false
        binding.nfcSetupContainer.isVisible = false
        binding.typingSetupContainer.isVisible = false
        binding.mathSetupContainer.isVisible = false
    }

    private fun updateSecondaryDropdown(challengeIndex: Int) {
        if (isOnEachOpenEnabled() &&
            challengeIndex == WarningUnlockSelectionMapper.NO_EFFORT_INDEX) {
            binding.secondaryBehaviorLayout.isVisible = false
            binding.secondaryBehaviorDropdown.setText(
                warningNoEffortOptions[WarningUnlockSelectionMapper.FIXED_TIME_INDEX].title,
                false
            )
            return
        }
        val options = when (challengeIndex) {
            WarningUnlockSelectionMapper.EFFORT_INDEX -> warningEffortOptions
            WarningUnlockSelectionMapper.NO_EFFORT_INDEX -> warningNoEffortOptions
            else -> null
        }
        if (options == null) {
            binding.secondaryBehaviorLayout.isVisible = false
        } else {
            binding.secondaryBehaviorDropdown.setAdapter(
                WarningUnlockOptionAdapter(fragment.requireContext(), options)
            )
            binding.secondaryBehaviorLayout.isVisible = true
        }
    }

    private fun updateUiVisibility(
        challengeIndex: Int,
        secondaryIndex: Int,
        animate: Boolean
    ) {
        if (animate) {
            TransitionManager.beginDelayedTransition(
                binding.mainContentContainer,
                AutoTransition()
            )
        }
        val usesSharedTiming = when (challengeIndex) {
            WarningUnlockSelectionMapper.NO_EFFORT_INDEX ->
                secondaryIndex == WarningUnlockSelectionMapper.FIXED_TIME_INDEX
            WarningUnlockSelectionMapper.EFFORT_INDEX ->
                secondaryIndex == 1 || secondaryIndex == 2 || secondaryIndex == 4
            else -> false
        }
        binding.timingContainer.isVisible = !isOnEachOpenEnabled() && usesSharedTiming
        binding.proceedDelayContainer.isVisible =
            challengeIndex != WarningUnlockSelectionMapper.NEVER_UNLOCK_INDEX &&
                challengeIndex != -1
        binding.qrSetupContainer.isVisible =
            challengeIndex == WarningUnlockSelectionMapper.EFFORT_INDEX &&
                secondaryIndex == 0
        binding.typingSetupContainer.isVisible =
            challengeIndex == WarningUnlockSelectionMapper.EFFORT_INDEX &&
                secondaryIndex == 1
        binding.intentSetupContainer.isVisible =
            challengeIndex == WarningUnlockSelectionMapper.EFFORT_INDEX &&
                secondaryIndex == 2
        binding.nfcSetupContainer.isVisible =
            challengeIndex == WarningUnlockSelectionMapper.EFFORT_INDEX &&
                secondaryIndex == 3
        binding.mathSetupContainer.isVisible =
            challengeIndex == WarningUnlockSelectionMapper.EFFORT_INDEX &&
                secondaryIndex == 4
    }

    private fun selectedChallengeIndex(): Int {
        val selectedTitle = binding.unlockChallengeDropdown.text.toString()
        return warningChallengeOptions.indexOfFirst { it.title == selectedTitle }
    }

    private fun selectedSecondaryIndex(challengeIndex: Int): Int {
        val selectedTitle = binding.secondaryBehaviorDropdown.text.toString()
        return secondaryOptions(challengeIndex).indexOfFirst { it.title == selectedTitle }
    }

    private fun secondaryOptions(challengeIndex: Int): List<WarningUnlockOption> {
        return when (challengeIndex) {
            WarningUnlockSelectionMapper.EFFORT_INDEX -> warningEffortOptions
            WarningUnlockSelectionMapper.NO_EFFORT_INDEX -> warningNoEffortOptions
            else -> emptyList()
        }
    }

    private fun showProceedWindowUnitMenu(anchor: View) {
        val options = unitOptions()
        PopupMenu(fragment.requireContext(), anchor).apply {
            options.forEachIndexed { index, option ->
                menu.add(0, index, index, option)
            }
            setOnMenuItemClickListener { item ->
                binding.proceedWindowUnitBtn.text = item.title
                updateProceedWindowSliderBounds(item.itemId)
                updateProceedWindowInput(
                    binding.proceedWindowSlider.value.toLong()
                )
                true
            }
            show()
        }
    }

    private fun updateProceedWindowSliderBounds(unitIndex: Int) {
        val (minimum, maximum) = when (unitIndex) {
            HOURS_INDEX -> 1f to 24f
            DAYS_INDEX -> 1f to 30f
            else -> 1f to 60f
        }
        val currentValue = binding.proceedWindowSlider.value
        val newValue = currentValue.coerceIn(minimum, maximum)
        if (currentValue < minimum || currentValue > maximum) {
            binding.proceedWindowSlider.value = minimum
        }
        binding.proceedWindowSlider.valueFrom = minimum
        binding.proceedWindowSlider.valueTo = maximum
        binding.proceedWindowSlider.value = newValue
    }

    private fun selectedProceedWindowMinutes(): Long {
        return numericInputValue(binding.proceedWindowInput) *
            minutesForUnit(selectedUnitIndex())
    }

    private fun selectedUnitIndex(): Int {
        return unitOptions().indexOf(binding.proceedWindowUnitBtn.text.toString())
            .coerceAtLeast(MINUTES_INDEX)
    }

    private fun minutesForUnit(unitIndex: Int): Long {
        return when (unitIndex) {
            HOURS_INDEX -> MINUTES_PER_HOUR
            DAYS_INDEX -> MINUTES_PER_DAY
            else -> 1L
        }
    }

    private fun updateProceedWindowInput(value: Long) {
        setNumericInputValue(binding.proceedWindowInput, value)
    }

    private fun updateFixedTimeInput(value: Long) {
        setNumericInputValue(binding.fixedTimeInput, value)
    }

    private fun updateProceedDelayInput(value: Long) {
        setNumericInputValue(binding.proceedDelayInput, value)
    }

    private fun updateAllowedProceedsInput(value: Long) {
        setNumericInputValue(binding.allowedProceedsInput, value)
    }

    private fun updateIntentMinLengthInput(value: Long) {
        setNumericInputValue(binding.intentMinLengthInput, value)
    }

    private fun updateMathQuestionCountInput(value: Long) {
        setNumericInputValue(binding.mathQuestionCountInput, value)
    }

    private fun updateMathStartingLevelInput(value: Long) {
        setNumericInputValue(binding.mathStartingLevelInput, value)
    }

    private fun updateFocusGoalMinutesInput(value: Long) {
        setNumericInputValue(binding.focusGoalMinutesInput, value)
    }

    private fun unitOptions(): List<String> {
        return listOf(
            fragment.getString(R.string.unit_minutes),
            fragment.getString(R.string.unit_hours),
            fragment.getString(R.string.unit_days)
        )
    }

    private fun numericInputValue(input: TextInputEditText): Long {
        return input.text?.toString()?.toLongOrNull() ?: 0L
    }

    private fun setNumericInputValue(input: TextInputEditText, value: Long) {
        val text = value.toString()
        if (input.text?.toString() != text) {
            input.setText(text)
        }
    }

    private companion object {
        const val MINUTES_INDEX = 0
        const val HOURS_INDEX = 1
        const val DAYS_INDEX = 2
        const val MINUTES_PER_HOUR = 60L
        const val MINUTES_PER_DAY = 1_440L
        const val MIN_MATH_QUESTIONS = 1
        const val MAX_MATH_QUESTIONS = 10
        const val MIN_MATH_LEVEL = 1
        const val MAX_MATH_LEVEL = 10
        const val MIN_FOCUS_MINUTES = 15
        const val MAX_FOCUS_MINUTES = 24 * 60
    }
}

private data class FocusGroupUnlockOption(
    val groupId: String,
    val name: String
) {
    override fun toString(): String = name
}

private data class NumericInput(
    val slider: Slider,
    val input: TextInputEditText,
    val inputLayout: TextInputLayout,
    val maximumValue: Long = Int.MAX_VALUE.toLong()
)
