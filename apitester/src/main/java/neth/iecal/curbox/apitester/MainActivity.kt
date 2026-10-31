package neth.iecal.curbox.apitester

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import neth.iecal.curbox.api.ICurboxApi
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * A tiny standalone app that drives the Curbox API end to end, the same way a real third party app
 * would: it finds Curbox, binds the service, asks the user for permission, then runs commands and
 * reads state. Every result is printed to the log pane at the bottom.
 */
class MainActivity : AppCompatActivity() {

    private val actionBind = "neth.iecal.curbox.api.BIND"
    private val actionRequestPermission = "neth.iecal.curbox.api.REQUEST_PERMISSION"

    private var api: ICurboxApi? = null
    private var curboxPackage: String? = null
    private var bound = false
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var targetField: EditText
    private lateinit var minutesField: EditText
    private lateinit var enableSwitch: MaterialSwitch

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            api = ICurboxApi.Stub.asInterface(service)
            bound = true
            val version = runCatching { api?.apiVersion() }.getOrNull()
            log("Connected to Curbox. apiVersion=$version")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            api = null
            bound = false
            log("Service disconnected.")
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val verdict = if (result.resultCode == RESULT_OK) "ALLOWED" else "DENIED or CANCELLED"
            log("Permission result: $verdict")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logView = findViewById(R.id.log)
        logScroll = findViewById(R.id.log_scroll)
        targetField = findViewById(R.id.field_target)
        minutesField = findViewById(R.id.field_minutes)
        enableSwitch = findViewById(R.id.switch_enable)

        findViewById<Button>(R.id.btn_connect).setOnClickListener { connect() }
        findViewById<Button>(R.id.btn_request).setOnClickListener { requestPermission() }
        findViewById<Button>(R.id.btn_granted).setOnClickListener { checkGranted() }

        findViewById<Button>(R.id.btn_start_focus).setOnClickListener {
            val args = Bundle().apply {
                putString("target", target())
                putInt("minutes", minutesField.text.toString().trim().toIntOrNull() ?: 25)
            }
            exec("START_FOCUS", args)
        }
        findViewById<Button>(R.id.btn_stop_focus).setOnClickListener { exec("STOP_FOCUS", Bundle()) }

        findViewById<Button>(R.id.btn_reel_blocker).setOnClickListener { execToggle("SET_REEL_BLOCKER") }
        findViewById<Button>(R.id.btn_keyword_blocker).setOnClickListener { execToggle("SET_KEYWORD_BLOCKER") }
        findViewById<Button>(R.id.btn_ui_hider).setOnClickListener { execToggle("SET_UI_HIDER") }
        findViewById<Button>(R.id.btn_reel_counter).setOnClickListener { execToggle("SET_REEL_COUNTER") }
        findViewById<Button>(R.id.btn_dnd).setOnClickListener { execToggle("SET_DND") }

        findViewById<Button>(R.id.btn_app_group).setOnClickListener { execTargetToggle("SET_APP_BLOCKER_GROUP") }
        findViewById<Button>(R.id.btn_keyword_group).setOnClickListener { execTargetToggle("SET_KEYWORD_GROUP") }
        findViewById<Button>(R.id.btn_grayscale_group).setOnClickListener { execTargetToggle("SET_GRAYSCALE_GROUP") }

        findViewById<Button>(R.id.btn_q_focus).setOnClickListener { query("FOCUS_ACTIVE") }
        findViewById<Button>(R.id.btn_q_screentime).setOnClickListener { query("SCREENTIME_TODAY") }
        findViewById<Button>(R.id.btn_q_reels).setOnClickListener { query("REELS_TODAY") }

        findViewById<Button>(R.id.btn_dump).setOnClickListener { dumpAll() }
        findViewById<Button>(R.id.btn_list_status).setOnClickListener { list("STATUS") }
        findViewById<Button>(R.id.btn_list_focus).setOnClickListener { list("FOCUS_GROUPS", autofillTarget = true) }
        findViewById<Button>(R.id.btn_list_app).setOnClickListener { list("APP_BLOCKER_GROUPS", autofillTarget = true) }
        findViewById<Button>(R.id.btn_list_keyword).setOnClickListener { list("KEYWORD_GROUPS", autofillTarget = true) }
        findViewById<Button>(R.id.btn_list_grayscale).setOnClickListener { list("GRAYSCALE_GROUPS", autofillTarget = true) }
        findViewById<Button>(R.id.btn_list_dnd).setOnClickListener { list("AUTO_DND_GROUPS") }
        findViewById<Button>(R.id.btn_list_uihider).setOnClickListener { list("UI_HIDER_SCRIPTS") }

        findViewById<Button>(R.id.btn_register_condition).setOnClickListener {
            exec("REGISTER_UNLOCK_CONDITION", Bundle().apply {
                putString("id", "practice_goal")
                putString("label", getString(R.string.api_tester_condition_label))
                putString("activity", "$packageName.SampleRequirementActivity")
            })
        }
        findViewById<Button>(R.id.btn_remove_condition).setOnClickListener {
            exec("REMOVE_UNLOCK_CONDITION", Bundle().apply { putString("id", "practice_goal") })
        }
        findViewById<Button>(R.id.btn_list_conditions).setOnClickListener { list("UNLOCK_CONDITIONS") }

        findViewById<Button>(R.id.btn_clear).setOnClickListener { logView.text = "" }

        log("Ready. Tap Connect, then Request permission, then run a command.")
    }

    private fun target(): String = targetField.text.toString().trim()

    /** Ask which installation to use when debug and release both expose the API. */
    private fun withCurbox(action: () -> Unit) {
        if (curboxPackage != null) {
            action()
            return
        }
        val services = packageManager.queryIntentServices(Intent(actionBind), 0)
            .distinctBy { it.serviceInfo.packageName }
        if (services.isEmpty()) {
            log(getString(R.string.api_tester_not_installed))
            return
        }
        fun select(index: Int) {
            curboxPackage = services[index].serviceInfo.packageName
            // SampleRequirementActivity only answers checks from this installation.
            getSharedPreferences(SampleRequirementActivity.PREFS, MODE_PRIVATE).edit()
                .putString(SampleRequirementActivity.KEY_CURBOX_PACKAGE, curboxPackage).apply()
            log(getString(R.string.api_tester_selected, curboxPackage))
            action()
        }
        if (services.size == 1) {
            select(0)
        } else {
            val labels = services.map {
                getString(R.string.api_tester_installation,
                    it.loadLabel(packageManager), it.serviceInfo.packageName)
            }.toTypedArray()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.api_tester_choose_installation)
                .setItems(labels) { _, index -> select(index) }
                .show()
        }
    }

    private fun connect() {
        if (bound) {
            log("Already connected.")
            return
        }
        withCurbox {
            val intent = Intent(actionBind).setPackage(curboxPackage)
            val ok = bindService(intent, connection, Context.BIND_AUTO_CREATE)
            log("bindService requested (returned $ok). Waiting for connection...")
        }
    }

    private fun requestPermission() {
        withCurbox {
            val intent = Intent(actionRequestPermission).setPackage(curboxPackage)
            try {
                permissionLauncher.launch(intent)
            } catch (e: Exception) {
                log("Could not open the permission screen: ${e.message}")
            }
        }
    }

    private fun checkGranted() {
        val a = api ?: return notConnected()
        io.execute {
            val granted = runCatching { a.isGranted }.getOrElse {
                log("isGranted failed: ${it.message}"); return@execute
            }
            log("isGranted = $granted")
        }
    }

    private fun execToggle(command: String) {
        val args = Bundle().apply { putBoolean("enable", enableSwitch.isChecked) }
        exec(command, args)
    }

    private fun execTargetToggle(command: String) {
        val args = Bundle().apply {
            putString("target", target())
            putBoolean("enable", enableSwitch.isChecked)
        }
        exec(command, args)
    }

    private fun exec(command: String, args: Bundle) {
        val a = api ?: return notConnected()
        io.execute {
            val res = runCatching {
                if (command in listOf("REGISTER_UNLOCK_CONDITION", "REMOVE_UNLOCK_CONDITION") &&
                    a.apiVersion() < 3) {
                    getString(R.string.api_tester_requires_v3, curboxPackage)
                } else {
                    a.execute(command, args)
                }
            }.getOrElse { "ERROR: ${it.message}" }
            log("execute($command) -> $res")
        }
    }

    private fun query(state: String) {
        val a = api ?: return notConnected()
        io.execute {
            val res = runCatching { a.query(state) }.getOrElse { "ERROR: ${it.message}" }
            log("query($state) -> ${pretty(res)}")
        }
    }

    private fun list(kind: String, autofillTarget: Boolean = false) {
        val a = api ?: return notConnected()
        io.execute {
            val res = runCatching { a.list(kind) }.getOrElse { "ERROR: ${it.message}" }
            log("list($kind) ->\n${pretty(res)}")
            if (autofillTarget && res != null) {
                firstId(res)?.let { id ->
                    runOnUiThread { targetField.setText(id) }
                    log("  (filled target with first id: $id)")
                }
            }
        }
    }

    /** Reads everything Curbox exposes in one shot, so you can see the whole picture at a glance. */
    private fun dumpAll() {
        val a = api ?: return notConnected()
        io.execute {
            log("================ CURBOX DUMP ================")
            for (kind in listOf(
                "UNLOCK_CONDITIONS", "STATUS", "FOCUS_GROUPS", "APP_BLOCKER_GROUPS", "KEYWORD_GROUPS",
                "GRAYSCALE_GROUPS", "AUTO_DND_GROUPS", "UI_HIDER_SCRIPTS"
            )) {
                val res = runCatching { a.list(kind) }.getOrElse { "ERROR: ${it.message}" }
                log("$kind ->\n${pretty(res)}")
            }
            for (state in listOf("SCREENTIME_TODAY", "REELS_TODAY")) {
                val res = runCatching { a.query(state) }.getOrElse { "ERROR: ${it.message}" }
                log("$state -> ${pretty(res)}")
            }
            log("================ END DUMP ================")
        }
    }

    /** Indents JSON so the log is readable. Falls back to the raw string if it is not JSON. */
    private fun pretty(s: String?): String {
        if (s == null) return "null (not allowed, or nothing to show)"
        val trimmed = s.trim()
        return try {
            when {
                trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
                trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
                else -> s
            }
        } catch (e: Exception) {
            s
        }
    }

    /** Pulls the "id" of the first item in a JSON array, for the auto fill convenience. */
    private fun firstId(s: String): String? {
        return try {
            val arr = JSONArray(s.trim())
            if (arr.length() == 0) null else arr.getJSONObject(0).optString("id").ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun notConnected() {
        log("Not connected yet. Tap Connect first.")
    }

    private fun log(message: String) {
        runOnUiThread {
            logView.append(message + "\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) runCatching { unbindService(connection) }
        io.shutdown()
    }
}
