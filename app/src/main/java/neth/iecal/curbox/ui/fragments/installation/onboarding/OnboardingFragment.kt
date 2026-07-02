package neth.iecal.curbox.ui.fragments.installation.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import neth.iecal.curbox.databinding.FragmentOnboardingBinding
import neth.iecal.curbox.ui.fragments.installation.onboarding.screens.EmpathyFragment
import neth.iecal.curbox.ui.fragments.installation.onboarding.screens.ScreenTimeEstimateFragment
import neth.iecal.curbox.ui.fragments.installation.onboarding.screens.CoreValuesFragment
import neth.iecal.curbox.ui.fragments.installation.onboarding.screens.TargetSelectionFragment
import neth.iecal.curbox.ui.fragments.installation.onboarding.screens.OnboardingPermissionsFragment

class OnboardingFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "onboarding_fragment"
    }

    private var _binding: FragmentOnboardingBinding? = null
    private val binding get() = _binding!!
    
    // Use activityViewModels so children fragments can share this ViewModel
    private val viewModel: OnboardingViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _binding = FragmentOnboardingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }
        val pagerAdapter = OnboardingPagerAdapter(this)
        binding.viewPager.adapter = pagerAdapter
        binding.viewPager.isUserInputEnabled = false 
        binding.viewPager.setPageTransformer(BlurFadePageTransformer())

        binding.viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                // Smooth background fade between steps
                val totalProgress = position + positionOffset
                val alpha = (totalProgress % 1.0f) * 0.2f
                binding.bgOverlay.alpha = alpha
            }
        })
    }

    private class BlurFadePageTransformer : ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {
            val absPos = Math.abs(position)
            // ViewPager2 mirrors its layout offsets in RTL, so the counteraction must flip sign too
            val isRtl = page.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val slideSign = if (isRtl) 1f else -1f

            page.apply {
                // Keep pages overlapping by counteracting the default slide
                translationX = slideSign * position * width

                // Fade out as it moves from center
                alpha = 1f - absPos

                // Gaussian blur effect (API 31+)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    if (absPos > 0f && absPos < 1f) {
                        val blurRadius = absPos * 40f
                        setRenderEffect(
                            android.graphics.RenderEffect.createBlurEffect(
                                blurRadius, blurRadius, android.graphics.Shader.TileMode.CLAMP
                            )
                        )
                    } else {
                        setRenderEffect(null)
                    }
                }

                // Subtle scale effect
                val scale = 0.95f + (1f - absPos) * 0.05f
                scaleX = scale
                scaleY = scale

                // Ensure the more visible page is on top
                translationZ = if (absPos < 1f) 1f else 0f
            }
        }
    }
    fun goToNextPage() {
        val currentItem = binding.viewPager.currentItem
        val itemCount = binding.viewPager.adapter?.itemCount ?: 0
        if (currentItem < itemCount - 1) {
            val nextItem = currentItem + 1
            val viewPager = binding.viewPager
            val width = viewPager.width
            val duration = 750L
            // Fake drag direction is mirrored in RTL, so flip the sign to advance forward
            val dragSign = if (viewPager.layoutDirection == View.LAYOUT_DIRECTION_RTL) 1f else -1f

            if (width > 0) {
                val animator = android.animation.ValueAnimator.ofFloat(0f, width.toFloat())
                var previousValue = 0f
                animator.addUpdateListener { valueAnimator ->
                    val currentValue = valueAnimator.animatedValue as Float
                    val delta = currentValue - previousValue
                    if (!viewPager.isFakeDragging) viewPager.beginFakeDrag()
                    viewPager.fakeDragBy(dragSign * delta)
                    previousValue = currentValue
                }
                animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (viewPager.isFakeDragging) viewPager.endFakeDrag()
                    }
                })
                animator.duration = duration
                animator.interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                animator.start()
            } else {
                viewPager.currentItem = nextItem
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private inner class OnboardingPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        // Onboarding never asks for a login. Sync lives in Settings (Play Store
        // only) and stays out of the way until someone goes looking for it.
        override fun getItemCount(): Int = 5

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> EmpathyFragment()
                1 -> ScreenTimeEstimateFragment()
                2 -> CoreValuesFragment()
                3 -> TargetSelectionFragment()
                4 -> OnboardingPermissionsFragment()
                else -> throw IllegalArgumentException("Invalid position $position")
            }
        }
    }
}
