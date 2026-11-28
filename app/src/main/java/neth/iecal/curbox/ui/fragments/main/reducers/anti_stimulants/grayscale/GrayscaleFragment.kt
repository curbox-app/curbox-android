package neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.grayscale

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.R
import neth.iecal.curbox.utils.GrayscaleControl
import neth.iecal.curbox.utils.ViewUtils
import neth.iecal.curbox.data.models.GrayscaleGroup
import neth.iecal.curbox.databinding.FragmentGrayscaleBinding
import neth.iecal.curbox.databinding.ItemGrayscaleGroupBinding
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.utils.PermissionUtils
import android.net.Uri

class GrayscaleFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "grayscale_fragment"
    }

    private var _binding: FragmentGrayscaleBinding? = null
    private val binding get() = _binding!!

    private val viewModel: GrayscaleViewModel by activityViewModels()

    private val shizukuPermissionListener =
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == 1001 && grantResult == PackageManager.PERMISSION_GRANTED) {
                activity?.runOnUiThread {
                    if (BuildConfig.SUPPORTS_WRITE_SECURE_SETTINGS) {
                        grantSecureSettings()
                    } else if (_binding != null) {
                        checkGrayscalePermission()
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGrayscaleBinding.inflate(inflater, container, false)
        
        binding.fabAddGroup.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", CreateGrayscaleGroupFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        binding.btnHelp.setOnClickListener {
            ViewUtils.showHelpPopup(it, getString(R.string.help_grayscale), "https://curbox.app/docs/reducers/grayscale/")
        }

        binding.rvGrayscaleGroups.layoutManager = LinearLayoutManager(requireContext())
        
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.groups.collectLatest { groups ->
                    if (groups.isEmpty()) {
                        binding.tvEmptyState.visibility = View.VISIBLE
                        binding.rvGrayscaleGroups.visibility = View.GONE
                    } else {
                        binding.tvEmptyState.visibility = View.GONE
                        binding.rvGrayscaleGroups.visibility = View.VISIBLE
                        binding.rvGrayscaleGroups.adapter = GrayscaleGroupAdapter(groups)
                    }
                }
            }
        }
    }

    inner class GrayscaleGroupAdapter(private val groupList: List<GrayscaleGroup>) :
        RecyclerView.Adapter<GrayscaleGroupAdapter.ViewHolder>() {

        inner class ViewHolder(val itemBinding: ItemGrayscaleGroupBinding) : RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ItemGrayscaleGroupBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val group = groupList[position]
            holder.itemBinding.tvGroupName.text = group.groupName
            
            holder.itemBinding.tvGroupDetails.text = getString(R.string.group_details_apps_only, group.packages.size)
            
            holder.itemBinding.switchActive.setOnCheckedChangeListener(null)
            holder.itemBinding.switchActive.isChecked = group.isActive
            holder.itemBinding.switchActive.setOnCheckedChangeListener { _, isChecked ->
                val updatedGroup = group.copy(isActive = isChecked)
                viewModel.updateGroup(updatedGroup)
            }
            
            holder.itemView.setOnClickListener {
                val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                    putExtra("fragment", CreateGrayscaleGroupFragment.FRAGMENT_ID)
                    putExtra("group_id", group.groupId)
                }
                startActivity(intent)
            }
        }

        override fun getItemCount() = groupList.size
    }

    override fun onResume() {
        super.onResume()
        checkGrayscalePermission()
    }

    private fun checkGrayscalePermission() {
        // Full and fdroid hold WRITE_SECURE_SETTINGS themselves; Shizuku is only
        // needed once to grant it. Playstore shells out via Shizuku on every toggle.
        val hasAccess = if (BuildConfig.SUPPORTS_WRITE_SECURE_SETTINGS) {
            PermissionUtils.hasWriteSecureSettings(requireContext())
        } else {
            PermissionUtils.hasShizukuPermission()
        }
        if (hasAccess) {
            binding.cardShizukuWarning.visibility = View.GONE
            return
        }

        binding.cardShizukuWarning.visibility = View.VISIBLE
        if (BuildConfig.SUPPORTS_WRITE_SECURE_SETTINGS) {
            binding.tvShizukuDesc.setText(R.string.grayscale_secure_settings_desc)
        }
        binding.btnActionShizuku.setOnClickListener {
            when {
                !PermissionUtils.isShizukuAvailable() -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/"))
                    startActivity(intent)
                }
                !PermissionUtils.hasShizukuPermission() -> {
                    try {
                        rikka.shizuku.Shizuku.requestPermission(1001)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                else -> grantSecureSettings()
            }
        }
    }

    private fun grantSecureSettings() {
        val context = context ?: return
        GrayscaleControl.grantWriteSecureSettings(context) {
            activity?.runOnUiThread {
                if (_binding != null) checkGrayscalePermission()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
