package neth.iecal.curbox.ui.fragments.main.focus

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import neth.iecal.curbox.nfc.NfcFocusHandler
import neth.iecal.curbox.nfc.NfcUnlockUtils
import neth.iecal.curbox.utils.ViewUtils
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.PomodoroPhase
import neth.iecal.curbox.databinding.FragmentFocusBinding
import androidx.core.view.isNotEmpty
import kotlin.math.abs

class FocusFragment : Fragment() {

    private var _binding: FragmentFocusBinding? = null
    private val binding get() = _binding!!

    private val viewModel: FocusViewModel by activityViewModels()

    private var isProgrammaticScroll = false
    private var itemWidthPx = 0
    private val snapHelper = LinearSnapHelper()
    private var nfcTapDialog: androidx.appcompat.app.AlertDialog? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFocusBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        viewModel.currentRunningFocus,
                        viewModel.groups,
                        viewModel.currentPomodoroState
                    ) { focus, groups, pomodoro ->
                        Triple(focus, groups, pomodoro)
                    }.collect { (focus, groups, pomodoro) ->
                        val b = _binding ?: return@collect
                        val (groupId, endTime) = focus
                        val isRunning = groupId != null
                        b.tvActiveGroup.visibility = if (isRunning) View.VISIBLE else View.GONE
                        b.btnGoToStats.visibility = if (isRunning) View.GONE else View.VISIBLE
                        // Adding time to a break would only make the break longer.
                        b.btnAddTime.visibility = if (isRunning && !(pomodoro.isActive && pomodoro.phase == PomodoroPhase.BREAK)) {
                            View.VISIBLE
                        } else {
                            View.GONE
                        }
                        b.tvSeconds.text = if (isRunning) "" else getString(R.string.common_mins)

                        if (isRunning) {
                            b.btnStartConfig.text = getString(
                                if (pomodoro.isActive) {
                                    R.string.focus_end_pomodoro
                                } else {
                                    R.string.focus_end_session
                                }
                            )
                        } else {
                            b.btnStartConfig.text = if (groups.isEmpty()) getString(R.string.focus_create_group) else getString(R.string.focus_start)
                        }

                        if (isRunning) {
                            b.rvRuler.stopScroll()
                            snapHelper.attachToRecyclerView(null)
                            val group = groups.find { it.groupId == groupId }
                            val groupName = group?.groupName ?: getString(R.string.focus_mode)
                            b.tvActiveGroup.text = if (pomodoro.isActive) {
                                when (pomodoro.phase) {
                                    PomodoroPhase.FOCUS -> getString(
                                        R.string.focus_pomodoro_focus_progress,
                                        groupName,
                                        pomodoro.currentFocusInterval,
                                        pomodoro.totalFocusIntervals
                                    )

                                    PomodoroPhase.BREAK -> getString(
                                        R.string.focus_pomodoro_break_progress,
                                        groupName,
                                        pomodoro.completedFocusIntervals,
                                        pomodoro.totalFocusIntervals
                                    )
                                }
                            } else {
                                groupName
                            }
                            b.btnStartConfig.isEnabled = group?.exitable == true
                            viewModel.startTimer(endTime)
                        } else {
                            snapHelper.attachToRecyclerView(b.rvRuler)
                            b.btnStartConfig.isEnabled = true
                            viewModel.stopTimer()
                            b.tvMinutes.text = viewModel.selectedMins.toString()
                            scrollToMinute(viewModel.selectedMins, smooth = false)
                        }
                    }
                }

                launch {
                    var lastTotalMinutesLeft = -1.0
                    var floatPixelAccumulator = 0.0

                    viewModel.currentRunningTimer.collect { time ->
                        val b = _binding ?: return@collect
                        val currentFocus = viewModel.currentRunningFocus.value
                        if (currentFocus.first != null && time > 0) {
                            val totalMinutesLeft = time / 60000.0
                            val minutes = (time / 60000).toInt()
                            val seconds = ((time % 60000) / 1000).toInt()

                            b.tvMinutes.text = minutes.toString()
                            b.tvSeconds.text = String.format(Locale.getDefault(), ":%02d", seconds)

                            if (b.rvRuler.width > 0 && b.rvRuler.isNotEmpty()) {
                                // Dynamically fetch the exact physical width of a rendered item
                                if (itemWidthPx > 0) {
                                    if (lastTotalMinutesLeft < 0 || abs(lastTotalMinutesLeft - totalMinutesLeft) > 1.0) {
                                        // Absolute (re)sync: place the centered tick on the remaining time.
                                        // scrollToPositionWithOffset places the item's left edge at
                                        // paddingLeft + offset, and paddingLeft already equals the
                                        // centering padding, so offset 0 centers integerPart. Shift it
                                        // left by the fractional part to land between two ticks.
                                        val fractionalPart = (totalMinutesLeft - totalMinutesLeft.toInt()).toFloat()
                                        val offset = -(fractionalPart * itemWidthPx).toInt()

                                        isProgrammaticScroll = true
                                        (b.rvRuler.layoutManager as LinearLayoutManager)
                                            .scrollToPositionWithOffset(totalMinutesLeft.toInt(), offset)
                                        isProgrammaticScroll = false // Reset instantly, onScrolled is synchronous

                                        floatPixelAccumulator = 0.0
                                    } else {
                                        // Smoothly scroll the per-tick delta to prevent layout thrashing.
                                        // Time decreases, so deltaMinutes > 0 and we scroll back (negative
                                        // dx) toward lower values, keeping the strip in sync with tvMinutes.
                                        val deltaMinutes = lastTotalMinutesLeft - totalMinutesLeft
                                        floatPixelAccumulator += deltaMinutes * itemWidthPx
                                        val pixelsToScroll = floatPixelAccumulator.toInt()

                                        if (pixelsToScroll != 0) {
                                            isProgrammaticScroll = true
                                            b.rvRuler.scrollBy(-pixelsToScroll, 0)
                                            isProgrammaticScroll = false
                                            floatPixelAccumulator -= pixelsToScroll
                                        }
                                    }
                                    lastTotalMinutesLeft = totalMinutesLeft
                                }
                            }
                        } else {
                            // Reset state when timer stops
                            lastTotalMinutesLeft = -1.0
                        }
                    }
                }
            }
        }
        setupRuler()
        setupClicks()
    }

    private fun setupClicks() {
        binding.btnGoToStats.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_holder, FocusStatsFragment())
                .addToBackStack(null)
                .commit()
        }

        binding.btnStartConfig.setOnClickListener {
            if (viewModel.currentRunningFocus.value.first != null) {
                viewModel.forceStopFocus()
            } else {
                FocusSetupBottomSheet().show(parentFragmentManager, FocusSetupBottomSheet.FRAGMENT_ID)
            }
        }

        binding.btnAddTime.setOnClickListener {
            showAddTimeDialog()
        }

        binding.btnHelp.setOnClickListener {
            ViewUtils.showHelpPopup(it, "Focus mode helps you stay away from distractions for a set period of time.", "https://curbox.app/docs/focus/focus-mode/")
        }

        binding.btnWriteFocusNfc.setOnClickListener {
            showWriteFocusNfcDialog()
        }
    }

    private fun showAddTimeDialog() {
        val labels = addTimeOptions.map { minutes ->
            val duration = if (minutes < 60) {
                getString(R.string.focus_duration_minutes_short, minutes)
            } else {
                getString(R.string.focus_duration_hours_short, minutes / 60)
            }
            getString(R.string.focus_add_time_option, duration)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.focus_add_time_title)
            .setItems(labels.toTypedArray()) { _, which ->
                viewModel.extendFocus(addTimeOptions[which])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private val addTimeOptions = listOf(5, 15, 30, 60)

    private val focusNfcActions = listOf("toggle", "start", "stop")

    private fun showWriteFocusNfcDialog() {
        val groups = viewModel.groups.value
        if (groups.isEmpty()) {
            Toast.makeText(requireContext(), R.string.focus_nfc_no_groups, Toast.LENGTH_LONG).show()
            return
        }

        val ctx = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_focus_nfc_write, null)
        val groupInput = view.findViewById<com.google.android.material.textfield.MaterialAutoCompleteTextView>(R.id.group_input)
        val actionInput = view.findViewById<com.google.android.material.textfield.MaterialAutoCompleteTextView>(R.id.action_input)
        val durationLayout = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.duration_layout)
        val durationInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.duration_input)

        val groupNames = groups.map { it.groupName.ifBlank { getString(R.string.focus_nfc_unnamed_group) } }
        val actionNames = listOf(
            getString(R.string.focus_nfc_action_toggle),
            getString(R.string.focus_nfc_action_start),
            getString(R.string.focus_nfc_action_stop)
        )
        groupInput.setSimpleItems(groupNames.toTypedArray())
        actionInput.setSimpleItems(actionNames.toTypedArray())

        var groupIdx = 0
        var actionIdx = 0
        groupInput.setText(groupNames[0], false)
        actionInput.setText(actionNames[0], false)
        durationInput.setText(viewModel.selectedMins.coerceAtLeast(1).toString())

        fun refreshDurationVisibility() {
            durationLayout.visibility = if (focusNfcActions[actionIdx] == "stop") View.GONE else View.VISIBLE
        }
        refreshDurationVisibility()

        groupInput.setOnItemClickListener { _, _, position, _ -> groupIdx = position }
        actionInput.setOnItemClickListener { _, _, position, _ ->
            actionIdx = position
            refreshDurationVisibility()
        }

        fun applyFromUri(uri: android.net.Uri) {
            val request = NfcFocusHandler.parse(uri.toString()) ?: return
            focusNfcActions.indexOf(request.action).takeIf { it >= 0 }?.let {
                actionIdx = it
                actionInput.setText(actionNames[it], false)
            }
            request.groupId?.let { gid ->
                groups.indexOfFirst { it.groupId == gid }.takeIf { it >= 0 }?.let {
                    groupIdx = it
                    groupInput.setText(groupNames[it], false)
                }
            }
            request.minutes?.coerceAtLeast(1)?.let {
                durationInput.setText(it.toString())
            }
            refreshDurationVisibility()
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.focus_nfc_write_title)
            .setView(view)
            .setPositiveButton(R.string.focus_nfc_write_button, null)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.focus_nfc_load_button, null)
            .create()

        // Freeze background resizing when the keyboard pops up, so the navbar doesn't jump around
        val hostWindow = activity?.window
        val prevSoftInput = hostWindow?.attributes?.softInputMode
        hostWindow?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        dialog.setOnDismissListener {
            prevSoftInput?.let { hostWindow.setSoftInputMode(it) }
        }

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                startFocusNfcLoad { uri -> applyFromUri(uri) }
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (activity?.let { NfcUnlockUtils.isNfcReady(it) } != true) {
                    Toast.makeText(requireContext(), R.string.nfc_unavailable, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val group = groups[groupIdx]
                val action = focusNfcActions[actionIdx]
                val mins = durationInput.text?.toString()?.toIntOrNull()?.coerceAtLeast(1)
                    ?: viewModel.selectedMins.coerceAtLeast(1)
                val uri = buildString {
                    append("curbox://focus/").append(action)
                    append("?group=").append(android.net.Uri.encode(group.groupId))
                    if (action != "stop") append("&mins=").append(mins)
                }
                dialog.dismiss()
                startFocusNfcWrite(uri)
            }
        }
        dialog.show()
    }

    /** The dedicated "tap your tag now" popup, shared by the write and load flows. */
    private fun showNfcTapDialog(titleRes: Int): androidx.appcompat.app.AlertDialog {
        val view = layoutInflater.inflate(R.layout.dialog_nfc_tap, null)
        view.findViewById<android.widget.TextView>(R.id.nfc_tap_title).setText(titleRes)
        return MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { stopFocusNfcWrite() }
            .show()
    }

    /** Enables reader mode, reads a curbox://focus URI from the tapped tag, and hands it to [onLoaded]. */
    private fun startFocusNfcLoad(onLoaded: (android.net.Uri) -> Unit) {
        val activity = activity ?: return
        if (!NfcUnlockUtils.isNfcReady(activity)) {
            Toast.makeText(requireContext(), R.string.nfc_unavailable, Toast.LENGTH_LONG).show()
            return
        }

        val enabled = NfcUnlockUtils.enableReader(activity) { tag ->
            NfcUnlockUtils.feedback(requireContext())
            val uri = NfcUnlockUtils.readUri(tag)
            if (uri != null && uri.scheme == "curbox" && uri.host == "focus") {
                onLoaded(uri)
                Toast.makeText(requireContext(), R.string.focus_nfc_loaded, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), R.string.focus_nfc_load_invalid, Toast.LENGTH_LONG).show()
            }
            stopFocusNfcWrite()
        }
        if (!enabled) {
            Toast.makeText(requireContext(), R.string.nfc_unavailable, Toast.LENGTH_LONG).show()
            return
        }

        nfcTapDialog = showNfcTapDialog(R.string.focus_nfc_load_title)
    }

    private fun startFocusNfcWrite(uri: String) {
        val activity = activity ?: return
        if (!NfcUnlockUtils.isNfcReady(activity)) {
            Toast.makeText(requireContext(), R.string.nfc_unavailable, Toast.LENGTH_LONG).show()
            return
        }

        val enabled = NfcUnlockUtils.enableReader(activity) { tag ->
            NfcUnlockUtils.feedback(requireContext())
            val ok = NfcUnlockUtils.writeUri(tag, uri)
            if (ok) NfcFocusHandler.markTagWritten(requireContext())
            Toast.makeText(
                requireContext(),
                if (ok) R.string.nfc_write_success else R.string.nfc_write_failed,
                Toast.LENGTH_LONG
            ).show()
            stopFocusNfcWrite()
        }
        if (!enabled) {
            Toast.makeText(requireContext(), R.string.nfc_unavailable, Toast.LENGTH_LONG).show()
            return
        }

        nfcTapDialog = showNfcTapDialog(R.string.nfc_tap_title)
    }

    private fun stopFocusNfcWrite() {
        activity?.let { NfcUnlockUtils.disableReader(it) }
        nfcTapDialog?.dismiss()
        nfcTapDialog = null
    }


    private fun updateTime(pos:Int){
        val b = _binding ?: return
        viewModel.selectedMins = pos.coerceAtLeast(1)
        b.tvMinutes.text = viewModel.selectedMins.toString()
        b.tvSeconds.text = getString(R.string.common_mins)
    }

    private fun setupRuler() {
        val bInitial = _binding ?: return
        val initialSelectedMins = viewModel.selectedMins
        isProgrammaticScroll = true

        val layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        bInitial.rvRuler.layoutManager = layoutManager
        bInitial.rvRuler.adapter = RulerAdapter()

        bInitial.rvRuler.setOnTouchListener { _, _ ->
            viewModel.currentRunningFocus.value.first != null
        }

        snapHelper.attachToRecyclerView(bInitial.rvRuler)

        bInitial.rvRuler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (isProgrammaticScroll || viewModel.currentRunningFocus.value.first != null) return
                val centerView = snapHelper.findSnapView(layoutManager) ?: return
                val pos = layoutManager.getPosition(centerView)
                updateTime(pos)
            }
        })

        bInitial.rvRuler.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val b = _binding ?: return
                if (b.rvRuler.width == 0) return
                b.rvRuler.viewTreeObserver.removeOnGlobalLayoutListener(this)

                itemWidthPx = (20 * resources.displayMetrics.density).toInt()
                val padding = (b.rvRuler.width / 2) - (itemWidthPx / 2)
                b.rvRuler.setPadding(padding, 0, padding, 0)
                b.rvRuler.clipToPadding = false

                b.rvRuler.post {
                    if (viewModel.currentRunningFocus.value.first == null) {
                        scrollToMinute(initialSelectedMins, smooth = false)
                    }
                }
            }
        })
    }

    private fun scrollToMinute(minutes: Int, smooth: Boolean = true) {
        val b = _binding ?: return
        val targetPos = minutes.coerceAtLeast(0)
        isProgrammaticScroll = true

        if (smooth) {
            b.rvRuler.smoothScrollToPosition(targetPos)
        } else {
            // paddingLeft already equals the centering padding, so offset 0 centers targetPos.
            (b.rvRuler.layoutManager as LinearLayoutManager)
                .scrollToPositionWithOffset(targetPos, 0)
        }

        b.rvRuler.postDelayed({ isProgrammaticScroll = false }, 300)
        if (!smooth) updateTime(minutes)
    }

    override fun onPause() {
        super.onPause()
        stopFocusNfcWrite()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopFocusNfcWrite()
        _binding = null
    }
}
