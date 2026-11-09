package neth.iecal.curbox.ui.fragments.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentContributorsBinding
import neth.iecal.curbox.ui.views.Contributor
import neth.iecal.curbox.ui.views.ContributorCloud

class ContributorsFragment : Fragment() {

    private var _binding: FragmentContributorsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentContributorsBinding.inflate(inflater, container, false)
        binding.toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadCloud()
    }

    private fun loadCloud() {
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val typeface = ResourcesCompat.getFont(context, R.font.inter)
            val cloud = try {
                withContext(Dispatchers.Default) {
                    ContributorCloud.build(readContributors(context), typeface)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
            binding.contributorsProgress.animate().alpha(0f).setDuration(200).withEndAction {
                _binding?.contributorsProgress?.visibility = View.GONE
            }
            if (cloud == null) return@launch

            binding.contributorsCloud.setCloud(cloud, typeface)
            binding.contributorsCloud.contentDescription = getString(
                R.string.contributors_cloud_description,
                cloud.words.filterNot { it.isFiller }.joinToString { it.contributor.name }
            )
            binding.contributorsCloud.animate().alpha(1f).setDuration(450).start()
        }
    }

    private fun readContributors(context: android.content.Context): List<Contributor> {
        val json = context.resources.openRawResource(R.raw.contributors).bufferedReader().use { it.readText() }
        val type = object : TypeToken<List<Contributor>>() {}.type
        return Gson().fromJson<List<Contributor>>(json, type).orEmpty()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
