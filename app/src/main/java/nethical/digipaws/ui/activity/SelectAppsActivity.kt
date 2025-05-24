package nethical.digipaws.ui.activity

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.SearchView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nethical.digipaws.R
import nethical.digipaws.data.blockers.PackageWand
import nethical.digipaws.databinding.ActivitySelectAppsBinding
import nethical.digipaws.databinding.DialogAddKeywordBinding

class SelectAppsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySelectAppsBinding
    private lateinit var selectedAppList: HashSet<String>

    private var appItemList: MutableList<AppItem> = mutableListOf()
    @SuppressLint("NotifyDataSetChanged")

    //change selectAll's text from "Select all" to "Clear all" and vice-versa
    private var allAppsSelected = false
    private fun updateSelectAllButton() {

        val adapter = binding.appList.adapter as? ApplicationAdapter

        // Check if every app currently in the adapter's list is present in selectedAppList
        allAppsSelected = adapter?.apps?.all { appItem ->
            selectedAppList.contains(appItem.packageName)
        } == true

        if (allAppsSelected) {
            binding.selectAll.text = getString(R.string.clear_all)
            allAppsSelected = true
        } else {
            binding.selectAll.text = getString(R.string.select_all)
            allAppsSelected = false
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySelectAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        selectedAppList =
            intent.getStringArrayListExtra("PRE_SELECTED_APPS")?.toHashSet() ?: HashSet()
        Log.d("pre-selected-apps", selectedAppList.toString())

        binding.appList.layoutManager = LinearLayoutManager(this)

        binding.selectAppsMagic.setOnClickListener {
            val popupMenu = PopupMenu(this, binding.selectAppsMagic)
            popupMenu.menuInflater.inflate(R.menu.app_wand, popupMenu.menu)
            popupMenu.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.select_social_media -> {
                        appItemList.forEach { item ->
                            if (PackageWand.SOCIAL_MEDIA_APPS.contains(item.packageName)) {
                                selectedAppList.add(item.packageName)
                            }
                        }
                        val slist = sortSelectedItemsToTop(appItemList)
                        (binding.appList.adapter as ApplicationAdapter).updateData(slist)
                        true
                    }
                    R.id.select_productive_apps -> {
                        appItemList.forEach { item ->
                            if (PackageWand.PRODUCTIVE_APPS.contains(item.packageName)) {
                                selectedAppList.add(item.packageName)
                            }
                        }
                        val slist = sortSelectedItemsToTop(appItemList)
                        (binding.appList.adapter as ApplicationAdapter).updateData(slist)
                        true
                    }
                    R.id.add_a_custom_package -> {
                        makeAddCustomPackageDialog()
                        true
                    }
                    else -> false
                }
            }
            popupMenu.show()
        }

        //manages the behaviour of the select all button
        binding.selectAll.setOnClickListener {
            val currentListAdapter = binding.appList.adapter as? ApplicationAdapter
            currentListAdapter?.let { adapter ->
                adapter.apps.forEach { appItem ->
                    if(allAppsSelected) {
                        selectedAppList.remove(appItem.packageName)
                    }
                    else {
                        selectedAppList.add(appItem.packageName)
                    }
                }
                adapter.notifyDataSetChanged() // To update checkboxes
            }
            updateSelectAllButton()
        }
        if (intent.hasExtra("APP_LIST")) {
            val appList = intent.getStringArrayListExtra("APP_LIST")
            appList?.forEach { packageName ->
                try {
                    val appInfo = packageManager.getApplicationInfo(packageName, 0)
                    appItemList.add(
                        AppItem(
                            packageName,
                            appInfo,
                            appInfo.loadLabel(packageManager).toString()
                        )
                    )
                } catch (e: Exception) {
                    appItemList.add(AppItem(packageName))
                }
            }
        } else {
            val launcherApps = getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
            val profiles = launcherApps.profiles
            val installedPackages = mutableSetOf<String>()

            // Load installed apps
            for (profile in profiles) {
                val apps = launcherApps.getActivityList(null, profile)
                    .map { it.applicationInfo }
                    .filter { it.packageName != packageName }
                apps.forEach { appInfo ->
                    installedPackages.add(appInfo.packageName)
                    val profileType = if (profile == Process.myUserHandle()) "" else "(Work)"
                    val appLabel = appInfo.loadLabel(packageManager).toString()
                    val displayName = "$appLabel $profileType"
                    appItemList.add(AppItem(appInfo.packageName, appInfo, displayName))
                }
            }

            // Add uninstalled apps from selectedAppList that aren't already included
            selectedAppList.forEach { packageName ->
                if (!installedPackages.contains(packageName)) {
                    try {
                        val appInfo = packageManager.getApplicationInfo(packageName, 0)
                        appItemList.add(
                            AppItem(
                                packageName,
                                appInfo,
                                appInfo.loadLabel(packageManager).toString()
                            )
                        )
                    } catch (e: Exception) {
                        appItemList.add(AppItem(packageName))
                    }
                }
            }

            appItemList.sortBy { it.displayName.lowercase() }
            updateSelectAllButton()
        }

        binding.confirmSelection.setOnClickListener {
            val selectedAppsArrayList = ArrayList(selectedAppList)
            val resultIntent = intent.apply {
                putStringArrayListExtra("SELECTED_APPS", selectedAppsArrayList)
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        }

        val sortedAppItemList = sortSelectedItemsToTop(appItemList)
        binding.appList.layoutManager = LinearLayoutManager(this)
        val filteredList = sortedAppItemList.toMutableList()
        binding.appList.adapter = ApplicationAdapter(filteredList, selectedAppList)

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            @SuppressLint("NotifyDataSetChanged")
            override fun onQueryTextChange(newText: String?): Boolean {
                val query = newText?.trim() ?: ""
                filteredList.clear()
                filteredList.addAll(
                    appItemList.filter {
                        it.displayName.contains(query, ignoreCase = true)
                    }
                )
                binding.appList.adapter?.notifyDataSetChanged()
                return true
            }
        })
        updateSelectAllButton()
    }

    fun sortSelectedItemsToTop(appItemList: List<AppItem>): List<AppItem> {
        return appItemList.sortedWith(compareBy<AppItem> {
            !selectedAppList.contains(it.packageName)
        }.thenBy {
            it.displayName.lowercase()
        })
    }

    inner class ApplicationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val appIcon: ImageView = itemView.findViewById(R.id.app_icon)
        val appName: TextView = itemView.findViewById(R.id.app_name)
        val checkbox: CheckBox = itemView.findViewById(R.id.checkbox)
    }

    inner class ApplicationAdapter(
        var apps: List<AppItem>,

        private val selectedAppList: HashSet<String>
    ) : RecyclerView.Adapter<ApplicationViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ApplicationViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.select_apps_item, parent, false)
            return ApplicationViewHolder(view)
        }

        override fun onBindViewHolder(holder: ApplicationViewHolder, position: Int) {
            val appItem = apps[position]

            holder.appIcon.setImageDrawable(null)
            holder.appName.text = appItem.displayName

            if (appItem.appInfo != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val packageManager = holder.itemView.context.packageManager
                    val icon = appItem.appInfo.loadIcon(packageManager)
                    withContext(Dispatchers.Main) {
                        holder.appIcon.setImageDrawable(icon)
                    }
                }
            } else {
                holder.appIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            holder.checkbox.setOnCheckedChangeListener(null)
            holder.checkbox.isChecked = selectedAppList.contains(appItem.packageName)

            holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedAppList.add(appItem.packageName)
                } else {
                    selectedAppList.remove(appItem.packageName)
                }
            }

            holder.itemView.setOnClickListener {
                holder.checkbox.isChecked = !holder.checkbox.isChecked
                updateSelectAllButton()
            }
        }

        override fun getItemCount(): Int = apps.size

        @SuppressLint("NotifyDataSetChanged")
        fun updateData(newList: List<AppItem>) {
            apps = newList
            notifyDataSetChanged()
        }
    }


    data class AppItem(
        val packageName: String,
        val appInfo: ApplicationInfo? = null,
        val displayName: String = packageName
    )

    private fun makeAddCustomPackageDialog() {
        val dialogBinding = DialogAddKeywordBinding.inflate(layoutInflater)
        dialogBinding.wHint.hint = "com.real.android.app"

        MaterialAlertDialogBuilder(this)
            .setTitle("Add a custom package")
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.add)) { dialog, _ ->
                val packageName = dialogBinding.keywordInput.text.toString().trim()
                if (packageName.isNotEmpty()) {
                    // Check if package already exists in the list
                    if (appItemList.none { it.packageName == packageName }) {
                        try {
                            // Try to get app info if it's installed
                            val appInfo = packageManager.getApplicationInfo(packageName, 0)
                            val appItem = AppItem(
                                packageName,
                                appInfo,
                                appInfo.loadLabel(packageManager).toString()
                            )
                            appItemList.add(appItem)
                        } catch (e: Exception) {
                            // Add as uninstalled package if not found
                            appItemList.add(AppItem(packageName))
                        }

                        // Update the adapter with the new sorted list
                        val sortedList = sortSelectedItemsToTop(appItemList)
                        val adapter = binding.appList.adapter as ApplicationAdapter
                        adapter.updateData(sortedList)

                        // Optionally add to selected list
                        selectedAppList.add(packageName)

                        Toast.makeText(this, "Added Successfully", Toast.LENGTH_SHORT).show()
                    } else {
                        // Show a message if package already exists
                        Toast.makeText(this, "Package already exists", Toast.LENGTH_SHORT).show()
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}