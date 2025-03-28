package nethical.digipaws.utils

import android.util.Log

class GrayscaleControl {
    private val commandListener = object : ShizukuRunner.CommandResultListener {
        override fun onCommandResult(output: String, done: Boolean) {
            Log.d("monochrome output: ",output)
            // Handle successful command execution if needed
        }

        override fun onCommandError(error: String) {
            // Handle command errors if needed

            Log.d("monochrome error: ",error)
        }
    }

    /**
     * Enable grayscale mode
     */
    fun enableGrayscale() {
        ShizukuRunner.executeCommand(
            "settings put secure accessibility_display_daltonizer 0 && " +
                    "settings put secure accessibility_display_daltonizer_enabled 1",
            commandListener
        )
    }

    /**
     * Disable grayscale mode
     */
    fun disableGrayscale() {
        ShizukuRunner.executeCommand(
            "settings put secure accessibility_display_daltonizer_enabled 0",
            commandListener
        )
    }

    /**
     * Toggle grayscale mode based on current state
     */
    fun toggleGrayscale() {
        ShizukuRunner.executeCommand(
            "if [ $(settings get secure accessibility_display_daltonizer_enabled) = \"1\" ]; then " +
                    "settings put secure accessibility_display_daltonizer_enabled 0; " +
                    "else " +
                    "settings put secure accessibility_display_daltonizer 0 && " +
                    "settings put secure accessibility_display_daltonizer_enabled 1; " +
                    "fi",
            commandListener
        )
    }

    /**
     * Get current grayscale state
     * @param callback Lambda that receives the current state (true if enabled, false if disabled)
     */
    fun isGrayscaleEnabled(callback: (Boolean) -> Unit) {
        ShizukuRunner.executeCommand(
            "settings get secure accessibility_display_daltonizer_enabled",
            object : ShizukuRunner.CommandResultListener {
                override fun onCommandResult(output: String, done: Boolean) {
                    if (done) {
                        callback(output.trim() == "1")
                    }
                }

                override fun onCommandError(error: String) {
                    callback(false)
                }
            }
        )
    }
}