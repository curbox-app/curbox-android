package nethical.digipaws.ui.activity

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import nethical.digipaws.R
import nethical.digipaws.ui.fragments.anti_uninstall.ChooseModeFragment
import nethical.digipaws.ui.fragments.installation.AccessibilityGuide
import nethical.digipaws.ui.fragments.installation.WelcomeFragment
import nethical.digipaws.ui.fragments.usage.AllAppsUsageFragment

class FragmentActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_fragment)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        var fragment: Fragment? = null
        if (intent.getStringExtra("fragment") != null) {
            when (intent.getStringExtra("fragment")) {
                ChooseModeFragment.FRAGMENT_ID -> {
                    fragment = ChooseModeFragment()
                }
                AllAppsUsageFragment.FRAGMENT_ID -> {
                    fragment = AllAppsUsageFragment()
                }
                WelcomeFragment.FRAGMENT_ID -> {
                    fragment = WelcomeFragment()
                }
                AccessibilityGuide.FRAGMENT_ID ->
                    fragment = AccessibilityGuide()
            }
            supportFragmentManager.beginTransaction()
                .replace(
                    R.id.fragment_holder,
                    fragment!!
                ) // Add or replace the fragment in the container
                .commit() // Commit the transaction
        }
    }
}