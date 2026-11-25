package neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.reel_counter

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.utils.ViewUtils
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentReelCounterBinding
import neth.iecal.curbox.ui.overlay.OverlayDragHelper
import neth.iecal.curbox.ui.views.ColorPickerDialog
import java.util.Locale

class ReelCounterFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "reel_counter"
    }

    private var _binding: FragmentReelCounterBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: ReelCounterViewModel
    private var isUpdatingUi = false
    private var positionScrim: View? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReelCounterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this)[ReelCounterViewModel::class.java]
        setupListeners()
        observeViewModel()
        viewModel.initialize()
    }

    private fun setupListeners() {
        binding.switchEnableCounter.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) viewModel.setIsActive(isChecked)
        }

        binding.btnHelp.setOnClickListener {
            ViewUtils.showHelpPopup(it, getString(R.string.help_reel_counter), "https://curbox.app/docs/reducers/video-counter/")
        }

        binding.btnPrevWeek.setOnClickListener { viewModel.goToPreviousWeek() }
        binding.btnNextWeek.setOnClickListener { viewModel.goToNextWeek() }

        binding.weeklyBarGraph.setOnBarSelectedListener { dayData ->
            val index = viewModel.weeklyData.value?.indexOf(dayData) ?: return@setOnBarSelectedListener
            if (index != -1) viewModel.selectDay(index)
        }

        binding.sliderReelTextSize.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            binding.tvReelTextSizeLabel.text = getString(
                R.string.reel_counter_text_size_value,
                value.toInt()
            )
            viewModel.updateOverlayConfig(viewModel.overlayConfig.value.copy(textSize = value))
        }

        binding.switchReelCheckpoints.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            updateCheckpointControlsVisibility(isChecked, animate = true)
            viewModel.updateOverlayConfig(
                viewModel.overlayConfig.value.copy(checkpointsEnabled = isChecked)
            )
        }

        binding.sliderReelCheckpointInterval.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val interval = value.toInt()
            binding.tvReelCheckpointIntervalLabel.text = getString(
                R.string.reel_checkpoint_interval_value,
                interval
            )
            viewModel.updateOverlayConfig(
                viewModel.overlayConfig.value.copy(checkpointInterval = interval)
            )
        }

        binding.sliderReelTextOpacity.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            binding.tvReelTextOpacityLabel.text = getString(
                R.string.reel_counter_text_opacity_value,
                value.toInt()
            )
            viewModel.updateOverlayConfig(viewModel.overlayConfig.value.copy(textOpacity = value.toInt()))
        }

        binding.sliderReelOpacity.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            binding.tvReelOpacityLabel.text = getString(
                R.string.reel_counter_background_opacity_value,
                value.toInt()
            )
            viewModel.updateOverlayConfig(viewModel.overlayConfig.value.copy(bgOpacity = value.toInt()))
        }

        binding.rowReelBackgroundColor.setOnClickListener {
            val config = viewModel.overlayConfig.value
            ColorPickerDialog.show(
                context = requireContext(),
                title = getString(R.string.reel_counter_background_color),
                initialColor = config.bgColor,
                onColorSelected = { color ->
                    viewModel.updateOverlayConfig(config.copy(bgColor = color))
                }
            )
        }

        binding.rowReelTextColor.setOnClickListener {
            val config = viewModel.overlayConfig.value
            ColorPickerDialog.show(
                context = requireContext(),
                title = getString(R.string.reel_counter_text_color),
                initialColor = config.textColor,
                onColorSelected = { color ->
                    viewModel.updateOverlayConfig(config.copy(textColor = color))
                }
            )
        }

        binding.btnSetPosition.setOnClickListener { showPositionDragOverlay() }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.settings.collectLatest { settings ->
                isUpdatingUi = true
                if (binding.switchEnableCounter.isChecked != settings.isReelCounterOn) {
                    binding.switchEnableCounter.isChecked = settings.isReelCounterOn
                }
                isUpdatingUi = false
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.overlayConfig.collectLatest { config ->
                isUpdatingUi = true

                if (binding.sliderReelTextSize.value != config.textSize) {
                    binding.sliderReelTextSize.value = config.textSize.coerceIn(24f, 120f)
                }
                binding.tvReelTextSizeLabel.text = getString(
                    R.string.reel_counter_text_size_value,
                    config.textSize.toInt()
                )

                if (binding.switchReelCheckpoints.isChecked != config.checkpointsEnabled) {
                    binding.switchReelCheckpoints.isChecked = config.checkpointsEnabled
                }
                updateCheckpointControlsVisibility(config.checkpointsEnabled, animate = false)

                val checkpointInterval = config.checkpointInterval.coerceIn(2, 100)
                if (binding.sliderReelCheckpointInterval.value != checkpointInterval.toFloat()) {
                    binding.sliderReelCheckpointInterval.value = checkpointInterval.toFloat()
                }
                binding.tvReelCheckpointIntervalLabel.text = getString(
                    R.string.reel_checkpoint_interval_value,
                    checkpointInterval
                )

                if (binding.sliderReelTextOpacity.value != config.textOpacity.toFloat()) {
                    binding.sliderReelTextOpacity.value = config.textOpacity.toFloat().coerceIn(0f, 100f)
                }
                binding.tvReelTextOpacityLabel.text = getString(
                    R.string.reel_counter_text_opacity_value,
                    config.textOpacity
                )

                if (binding.sliderReelOpacity.value != config.bgOpacity.toFloat()) {
                    binding.sliderReelOpacity.value = config.bgOpacity.toFloat().coerceIn(0f, 100f)
                }
                binding.tvReelOpacityLabel.text = getString(
                    R.string.reel_counter_background_opacity_value,
                    config.bgOpacity
                )

                binding.reelBackgroundColorPreview.setCardBackgroundColor(toOpaqueColor(config.bgColor))
                binding.tvReelBackgroundColorHex.text = colorHex(config.bgColor)
                binding.reelTextColorPreview.setCardBackgroundColor(toOpaqueColor(config.textColor))
                binding.tvReelTextColorHex.text = colorHex(config.textColor)

                isUpdatingUi = false
            }
        }

        viewModel.weeklyData.observe(viewLifecycleOwner) { data ->
            binding.weeklyBarGraph.setData(data, viewModel.selectedDayIndex.value ?: 6)
        }

        viewModel.selectedDayIndex.observe(viewLifecycleOwner) { index ->
            binding.weeklyBarGraph.setSelectedIndex(index)
        }

        viewModel.selectedDayTotal.observe(viewLifecycleOwner) { count ->
            binding.totalReelsCount.text = count.toString()
        }

        viewModel.dateSublabel.observe(viewLifecycleOwner) { label ->
            binding.dateSublabel.text = label
        }

        viewModel.weekRangeLabel.observe(viewLifecycleOwner) { label ->
            binding.tvWeekRange.text = label
        }

        viewModel.canGoNext.observe(viewLifecycleOwner) { canGo ->
            binding.btnNextWeek.alpha = if (canGo) 1f else 0.3f
            binding.btnNextWeek.isEnabled = canGo
        }
    }

    private fun showPositionDragOverlay() {
        if (positionScrim != null) return
        val config = viewModel.overlayConfig.value
        positionScrim = OverlayDragHelper.showDragOverlay(
            fragment = this,
            layoutResId = R.layout.overlay_usage_stat,
            positionX = config.positionX,
            positionY = config.positionY,
            setupWidget = { widget ->
                val r = (config.bgColor shr 16) and 0xFF
                val g = (config.bgColor shr 8) and 0xFF
                val b = config.bgColor and 0xFF
                widget.setBackgroundColor(Color.argb(config.bgOpacity * 255 / 100, r, g, b))
                widget.findViewById<TextView>(R.id.reel_counter).apply {
                    visibility = View.VISIBLE
                    text = "42"
                    textSize = config.textSize
                    setTextColor(toOpaqueColor(config.textColor))
                    alpha = config.textOpacity / 100f
                }
                widget.findViewById<TextView>(R.id.time_elapsed_txt).apply {
                    visibility = View.GONE
                }
            },
            onPositionSaved = { x, y ->
                viewModel.updateOverlayConfig(config.copy(positionX = x, positionY = y))
            },
            onDismiss = { positionScrim = null }
        )
    }

    private fun colorHex(color: Int): String =
        String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)

    private fun updateCheckpointControlsVisibility(isVisible: Boolean, animate: Boolean) {
        val container = binding.reelCheckpointIntervalContainer
        container.animate().cancel()
        if (isVisible) {
            if (container.visibility != View.VISIBLE) {
                container.alpha = if (animate) 0f else 1f
                container.visibility = View.VISIBLE
            }
            if (animate) {
                container.animate().alpha(1f).setDuration(200).start()
            } else {
                container.alpha = 1f
            }
        } else if (animate && container.visibility == View.VISIBLE) {
            container.animate().alpha(0f).setDuration(160).withEndAction {
                if (!binding.switchReelCheckpoints.isChecked) {
                    container.visibility = View.GONE
                }
            }.start()
        } else {
            container.alpha = 0f
            container.visibility = View.GONE
        }
    }

    private fun toOpaqueColor(color: Int): Int = Color.rgb(
        (color shr 16) and 0xFF,
        (color shr 8) and 0xFF,
        color and 0xFF
    )

    override fun onDestroyView() {
        positionScrim?.let {
            (activity?.window?.decorView as? FrameLayout)?.removeView(it)
            positionScrim = null
        }
        super.onDestroyView()
        _binding = null
    }
}
