package neth.iecal.curbox.ui.fragments.main.reducers

import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import neth.iecal.curbox.R

import android.content.Intent
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.utils.ViewUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.grayscale.GrayscaleFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker.AppBlockerGroupsFragment

class ReducersFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_reducers, container, false)
        val appBlockerCard = view.findViewById<MaterialCardView>(R.id.card_app_blocker)
        val reelBlockerCard = view.findViewById<MaterialCardView>(R.id.card_reels_blocker)
        val keywordBlockerCard = view.findViewById<MaterialCardView>(R.id.card_keyword_blocker)
        val autoDndCard = view.findViewById<MaterialCardView>(R.id.card_autodnd)
        
        view.findViewById<MaterialButton>(R.id.btn_help).setOnClickListener {
            ViewUtils.showHelpPopup(it, "Blocker tools and stimulation reducers help you regain focus.", "https://curbox.app/docs/reducers/overview/")
        }

        appBlockerCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", AppBlockerGroupsFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }
        
        reelBlockerCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.blockertools.reelBlocker.ReelBlockerFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        keywordBlockerCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.blockertools.keywordBlocker.KeywordBlockerFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        autoDndCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.blockertools.autodnd.AutoDndFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        val reelCounterCard = view.findViewById<MaterialCardView>(R.id.card_reel_counter)
        reelCounterCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.reel_counter.ReelCounterFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }
        
        val grayscaleCard = view.findViewById<MaterialCardView>(R.id.card_grayscale)
        grayscaleCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", GrayscaleFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }
        
        val intentsCard = view.findViewById<MaterialCardView>(R.id.card_intents)
        intentsCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.mindful_messages.MindfulMessagesFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        val uiHiderCard = view.findViewById<MaterialCardView>(R.id.card_ui_hider)
        uiHiderCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.blockertools.uiHider.UiHiderFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        val intentsLogCard = view.findViewById<MaterialCardView>(R.id.card_logged_intents)
        intentsLogCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.analytics.IntentsLogFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        val apiCard = view.findViewById<MaterialCardView>(R.id.card_api)
        apiCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.api.ApiFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        val antiUninstallCard = view.findViewById<MaterialCardView>(R.id.card_anti_uninstall)
        antiUninstallCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.advanced.AntiUninstallFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        val serviceProtectionCard = view.findViewById<MaterialCardView>(R.id.card_service_protection)
        serviceProtectionCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.advanced.ServiceProtectionFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        val advancedHeader = view.findViewById<View>(R.id.advanced_header)
        val advancedContent = view.findViewById<View>(R.id.advanced_content)
        val advancedArrow = view.findViewById<ImageView>(R.id.advanced_arrow)
        advancedHeader.setOnClickListener {
            val isVisible = advancedContent.isVisible
            TransitionManager.beginDelayedTransition(view.findViewById(R.id.reducers_root), AutoTransition())
            advancedContent.isVisible = !isVisible
            advancedArrow.animate().rotation(if (isVisible) 0f else 90f).start()
        }

        return view
    }
}
