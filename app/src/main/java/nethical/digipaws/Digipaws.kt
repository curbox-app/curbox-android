package nethical.digipaws

import android.app.Application
import com.google.android.material.color.DynamicColors

class Digipaws: Application() {
  override fun onCreate() {
    DynamicColors.applyToActivitiesIfAvailable(this)
    super.onCreate()
  }
}
