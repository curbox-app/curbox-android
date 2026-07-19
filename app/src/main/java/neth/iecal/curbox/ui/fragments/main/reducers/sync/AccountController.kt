package neth.iecal.curbox.ui.fragments.main.reducers.sync

import android.view.View
import android.widget.ImageView
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.sync.SyncBillingStatus
import neth.iecal.curbox.data.sync.SyncGateway
import neth.iecal.curbox.data.sync.SyncStatus
import neth.iecal.curbox.data.sync.SyncPreferences

/**
 * Drives the whole account experience over a view_account layout: sign in, sign
 * up, the emailed code, password reset, the secret phrase, and pairing. Shared
 * by the standalone Sync screen and the onboarding step so both look and behave
 * the same.
 */
class AccountController(
    private val root: View,
    private val fragment: Fragment,
    private val requestScan: () -> Unit,
) {
    private enum class Mode { SIGN_IN, SIGN_UP, FORGOT, RESET }

    private companion object {
        // How long the pairing QR stays on screen before it hides itself.
        const val PAIRING_CODE_VISIBLE_MS = 60_000L
    }

    private val provider get() = SyncGateway.provider

    private var mode = Mode.SIGN_IN
    private var last = SyncStatus()
    private var lastBilling = SyncBillingStatus()

    private val title = root.findViewById<TextView>(R.id.text_title)
    private val subtitle = root.findViewById<TextView>(R.id.text_subtitle)
    private val message = root.findViewById<TextView>(R.id.text_message)
    private val progress = root.findViewById<LinearProgressIndicator>(R.id.progress_account)

    private val email = root.findViewById<TextInputEditText>(R.id.input_email)
    private val password = root.findViewById<TextInputEditText>(R.id.input_password)
    private val code = root.findViewById<TextInputEditText>(R.id.input_code)
    private val forgotEmail = root.findViewById<TextInputEditText>(R.id.input_forgot_email)
    private val resetCode = root.findViewById<TextInputEditText>(R.id.input_reset_code)
    private val newPassword = root.findViewById<TextInputEditText>(R.id.input_new_password)
    private val passphrase = root.findViewById<TextInputEditText>(R.id.input_passphrase)
    private val pairInput = root.findViewById<TextInputEditText>(R.id.input_pair)
    private val deviceName = root.findViewById<TextInputEditText>(R.id.input_device_name)
    private val syncUsage = root.findViewById<MaterialSwitch>(R.id.switch_sync_usage)
    private val syncConfigs = root.findViewById<MaterialSwitch>(R.id.switch_sync_configs)
    private val deviceFilterBlock = root.findViewById<View>(R.id.device_filter_block)
    private val deviceFilterList = root.findViewById<LinearLayout>(R.id.device_filter_list)
    private var applyingControls = false

    private val sectionPaywall = root.findViewById<View>(R.id.section_paywall)
    private val paywallPrice = root.findViewById<TextView>(R.id.text_paywall_price)
    private val sectionAuth = root.findViewById<View>(R.id.section_auth)
    private val sectionVerify = root.findViewById<View>(R.id.section_verify)
    private val sectionForgot = root.findViewById<View>(R.id.section_forgot)
    private val sectionReset = root.findViewById<View>(R.id.section_reset)
    private val sectionPassphrase = root.findViewById<View>(R.id.section_passphrase)
    private val pairingBlock = root.findViewById<View>(R.id.pairing_block)
    private val sectionUnlocked = root.findViewById<View>(R.id.section_unlocked)

    private val primaryAuth = root.findViewById<MaterialButton>(R.id.btn_primary_auth)
    private val togglePrompt = root.findViewById<TextView>(R.id.text_toggle_prompt)
    private val toggleMode = root.findViewById<TextView>(R.id.btn_toggle_mode)
    private val passphraseBtn = root.findViewById<MaterialButton>(R.id.btn_passphrase)
    private val qr = root.findViewById<ImageView>(R.id.image_qr)

    fun bind() {
        primaryAuth.setOnClickListener { btn ->
            if (mode == Mode.SIGN_UP) {
                submit(button = btn) { provider.signUp(text(email), text(password)) }
            } else {
                submit(button = btn) { provider.signIn(text(email), text(password)) }
            }
        }
        toggleMode.setOnClickListener {
            mode = if (mode == Mode.SIGN_IN) Mode.SIGN_UP else Mode.SIGN_IN
            apply()
        }
        root.findViewById<View>(R.id.btn_forgot).setOnClickListener { mode = Mode.FORGOT; apply() }
        root.findViewById<View>(R.id.btn_forgot_back).setOnClickListener { mode = Mode.SIGN_IN; apply() }

        root.findViewById<View>(R.id.btn_verify).setOnClickListener { btn ->
            val e = last.pendingEmail ?: return@setOnClickListener
            submit(button = btn) { provider.verifySignupCode(e, text(code)) }
        }
        root.findViewById<View>(R.id.btn_resend).setOnClickListener { btn ->
            val e = last.pendingEmail ?: return@setOnClickListener
            submit(toast = fragment.getString(R.string.account_msg_new_code_sent, e), button = btn) { provider.resendSignupCode(e) }
        }
        root.findViewById<View>(R.id.btn_send_reset).setOnClickListener { btn ->
            val e = text(forgotEmail)
            submit(toast = fragment.getString(R.string.account_msg_reset_code_sent, e), button = btn) {
                provider.sendPasswordReset(e); resetEmail = e; mode = Mode.RESET; apply()
            }
        }
        root.findViewById<View>(R.id.btn_set_password).setOnClickListener { btn ->
            submit(button = btn) { provider.resetPassword(resetEmail, text(resetCode), text(newPassword)) }
        }

        passphraseBtn.setOnClickListener { btn ->
            val phrase = text(passphrase)
            if (!last.hasVault && phrase.length < 8) {
                fragment.context?.let { Toast.makeText(it, R.string.account_msg_phrase_too_short, Toast.LENGTH_LONG).show() }
                return@setOnClickListener
            }
            submit(button = btn) { if (last.hasVault) provider.unlock(phrase) else provider.setPassphrase(phrase) }
        }
        root.findViewById<View>(R.id.btn_pair).setOnClickListener { btn -> submit(button = btn) { provider.pairWithCode(text(pairInput)) } }
        root.findViewById<View>(R.id.btn_scan).setOnClickListener { requestScan() }
        root.findViewById<View>(R.id.btn_show_code).setOnClickListener { btn ->
            // The code contains the raw secret key, so the user confirms first and
            // the QR takes itself down instead of sitting on screen.
            val ctx = fragment.context ?: return@setOnClickListener
            com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.pairing_code_warning_title)
                .setMessage(R.string.pairing_code_warning_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.pairing_code_show) { _, _ -> showPairingCode(btn) }
                .show()
        }
        root.findViewById<View>(R.id.btn_force_sync).setOnClickListener { btn ->
            submit(toast = fragment.getString(R.string.account_msg_syncing), button = btn) { provider.pushNow(); provider.refresh() }
        }
        root.findViewById<View>(R.id.btn_save_device_name).setOnClickListener { btn ->
            submit(toast = fragment.getString(R.string.account_save_device_name), button = btn) {
                provider.setDeviceName(text(deviceName))
            }
        }
        syncUsage.setOnCheckedChangeListener { _, checked ->
            if (!applyingControls) savePreferences(last.preferences.copy(usageStats = checked))
        }
        syncConfigs.setOnCheckedChangeListener { _, checked ->
            if (!applyingControls) savePreferences(last.preferences.copy(reducerConfigs = checked))
        }
        root.findViewById<View>(R.id.btn_signout).setOnClickListener { btn -> submit(button = btn) { provider.signOut() } }

        root.findViewById<View>(R.id.btn_subscribe).setOnClickListener {
            fragment.activity?.let { provider.launchBillingFlow(it) }
        }
        root.findViewById<View>(R.id.btn_restore_purchase).setOnClickListener {
            provider.refreshBilling()
            fragment.context?.let { Toast.makeText(it, R.string.sync_paywall_checking, Toast.LENGTH_SHORT).show() }
        }

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                provider.status.collect { last = it; apply() }
            }
        }
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                provider.billing.collect { lastBilling = it; apply() }
            }
        }
        // Purchases made or refunded outside the app show up on the next check.
        provider.refreshBilling()
        apply()
    }

    fun pairWith(payload: String) = submit { provider.pairWithCode(payload) }

    private var resetEmail = ""

    private fun apply() {
        val signedIn = last.signedIn
        val unlocked = last.unlocked
        val verifying = !signedIn && last.pendingEmail != null

        // Sync is the one paid feature. When this build sells it and there is no
        // active subscription, the paywall replaces the whole account flow.
        val needsSubscription = lastBilling.required && !lastBilling.entitled
        sectionPaywall.visibility = vis(needsSubscription)
        if (needsSubscription) {
            paywallPrice.text = fragment.getString(
                R.string.sync_paywall_price,
                lastBilling.price ?: fragment.getString(R.string.sync_paywall_price_fallback),
            )
            sectionAuth.visibility = View.GONE
            sectionForgot.visibility = View.GONE
            sectionReset.visibility = View.GONE
            sectionVerify.visibility = View.GONE
            sectionPassphrase.visibility = View.GONE
            pairingBlock.visibility = View.GONE
            sectionUnlocked.visibility = View.GONE
            qr.visibility = View.GONE
            return
        }

        sectionAuth.visibility = vis(!signedIn && !verifying && (mode == Mode.SIGN_IN || mode == Mode.SIGN_UP))
        sectionForgot.visibility = vis(!signedIn && !verifying && mode == Mode.FORGOT)
        sectionReset.visibility = vis(!signedIn && !verifying && mode == Mode.RESET)
        sectionVerify.visibility = vis(verifying)
        sectionPassphrase.visibility = vis(signedIn && !unlocked)
        // Pairing only makes sense once a passphrase already exists on another
        // device. On the very first device there is nothing to pair with, so it
        // would only confuse. Hide it until a vault exists.
        pairingBlock.visibility = vis(signedIn && !unlocked && last.hasVault)
        sectionUnlocked.visibility = vis(unlocked)
        if (!unlocked) qr.visibility = View.GONE

        if (mode == Mode.SIGN_UP) {
            primaryAuth.text = fragment.getString(R.string.account_create_account_action)
            togglePrompt.text = fragment.getString(R.string.account_already_have_account)
            toggleMode.text = fragment.getString(R.string.account_sign_in)
        } else {
            primaryAuth.text = fragment.getString(R.string.account_sign_in)
            togglePrompt.text = fragment.getString(R.string.account_new_here)
            toggleMode.text = fragment.getString(R.string.account_create_account)
        }

        if (signedIn && !unlocked) {
            passphraseBtn.text = fragment.getString(if (last.hasVault) R.string.account_unlock_data else R.string.account_turn_on_sync)
        }

        if (unlocked) applySyncControls()

        title.text = when {
            unlocked -> fragment.getString(R.string.account_title_sync_on)
            signedIn -> fragment.getString(if (last.hasVault) R.string.account_unlock_data else R.string.account_make_secret_phrase)
            verifying -> fragment.getString(R.string.account_title_check_email)
            mode == Mode.FORGOT -> fragment.getString(R.string.account_title_reset_password)
            mode == Mode.RESET -> fragment.getString(R.string.account_title_choose_new_password)
            mode == Mode.SIGN_UP -> fragment.getString(R.string.account_title_create_account)
            else -> fragment.getString(R.string.account_title_welcome_back)
        }

        subtitle.text = when {
            unlocked -> fragment.getString(R.string.account_sub_sync_on)
            signedIn && last.hasVault -> fragment.getString(R.string.account_sub_unlock)
            signedIn -> fragment.getString(R.string.account_sub_make_phrase)
            verifying -> fragment.getString(R.string.account_sub_verify, last.pendingEmail)
            mode == Mode.FORGOT -> fragment.getString(R.string.account_sub_forgot)
            mode == Mode.RESET -> fragment.getString(R.string.account_sub_reset)
            mode == Mode.SIGN_UP -> fragment.getString(R.string.account_sub_sign_up)
            else -> fragment.getString(R.string.account_sub_sign_in)
        }

        val msg = last.error
        message.visibility = if (msg != null) View.VISIBLE else View.GONE
        if (msg != null) message.text = msg
    }

    private fun applySyncControls() {
        applyingControls = true
        val current = last.devices.firstOrNull { it.current }
        if (!deviceName.hasFocus() && current != null && text(deviceName) != current.label) {
            deviceName.setText(current.label)
        }
        syncUsage.isChecked = last.preferences.usageStats
        syncConfigs.isChecked = last.preferences.reducerConfigs
        deviceFilterBlock.visibility = vis(last.preferences.usageStats && last.devices.any { !it.current })
        deviceFilterList.removeAllViews()

        val ctx = fragment.context
        if (ctx != null && deviceFilterBlock.visibility == View.VISIBLE) {
            deviceFilterList.addView(CheckBox(ctx).apply {
                text = fragment.getString(R.string.account_all_devices)
                isChecked = last.preferences.usageDeviceIds.isEmpty()
                setOnCheckedChangeListener { _, checked ->
                    if (checked && !applyingControls) savePreferences(last.preferences.copy(usageDeviceIds = emptySet()))
                }
            })
            last.devices.filterNot { it.current }.forEach { device ->
                deviceFilterList.addView(CheckBox(ctx).apply {
                    text = "${device.label} · ${device.platform}"
                    isChecked = device.id in last.preferences.usageDeviceIds
                    setOnCheckedChangeListener { _, checked ->
                        if (applyingControls) return@setOnCheckedChangeListener
                        val ids = last.preferences.usageDeviceIds.toMutableSet()
                        if (checked) ids.add(device.id) else ids.remove(device.id)
                        savePreferences(last.preferences.copy(usageDeviceIds = ids))
                    }
                })
            }
        }
        applyingControls = false
    }

    private fun savePreferences(preferences: SyncPreferences) = submit {
        provider.setPreferences(preferences)
    }

    private fun vis(show: Boolean) = if (show) View.VISIBLE else View.GONE

    private fun text(field: TextInputEditText): String = field.text?.toString()?.trim().orEmpty()

    // Generating the code is the only part that waits on anything; the 60s
    // auto-hide afterwards is not "loading" and must not keep the button
    // disabled or the progress bar spinning for a full minute.
    private fun showPairingCode(button: View) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            beginLoading(button)
            try {
                val payload = provider.makePairingCode()
                val bmp = BarcodeEncoder().encodeBitmap(payload, BarcodeFormat.QR_CODE, 600, 600)
                qr.setImageBitmap(bmp)
                qr.visibility = View.VISIBLE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fragment.context?.let { Toast.makeText(it, friendly(e.message), Toast.LENGTH_LONG).show() }
                return@launch
            } finally {
                endLoading(button)
            }
            delay(PAIRING_CODE_VISIBLE_MS)
            qr.setImageBitmap(null)
            qr.visibility = View.GONE
        }
    }

    private fun beginLoading(button: View?) {
        button?.isEnabled = false
        progress.visibility = View.VISIBLE
    }

    private fun endLoading(button: View?) {
        button?.isEnabled = true
        progress.visibility = View.GONE
    }

    private fun submit(toast: String? = null, button: View? = null, block: suspend () -> Unit) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            beginLoading(button)
            try {
                block()
                fragment.context?.let { ctx -> if (toast != null) Toast.makeText(ctx, toast, Toast.LENGTH_LONG).show() }
            } catch (e: CancellationException) {
                // The fragment's view is on its way out (activity finished, back
                // pressed mid-request, etc). Let it die quietly instead of
                // trying to toast on a context that no longer exists.
                throw e
            } catch (e: Exception) {
                fragment.context?.let { ctx -> Toast.makeText(ctx, friendly(e.message), Toast.LENGTH_LONG).show() }
            } finally {
                endLoading(button)
            }
        }
    }

    // Turns raw server and network errors into calm, plain language. Keeps the
    // reader unworried and tells them what to do next.
    private fun friendly(raw: String?): String {
        val m = raw?.lowercase() ?: return fragment.getString(R.string.account_err_generic)
        return when {
            "invalid login" in m || ("invalid" in m && "credential" in m) ->
                fragment.getString(R.string.account_err_invalid_login)
            "already registered" in m || "already been registered" in m ->
                fragment.getString(R.string.account_err_already_registered)
            "email not confirmed" in m -> fragment.getString(R.string.account_err_email_not_confirmed)
            "token has expired" in m || "otp" in m || ("invalid" in m && "code" in m) ->
                fragment.getString(R.string.account_err_bad_code)
            "for security purposes" in m || "rate limit" in m || "too many" in m ->
                fragment.getString(R.string.account_err_rate_limit)
            "password should be" in m || "at least" in m && "character" in m ->
                fragment.getString(R.string.account_err_weak_password)
            "unable to resolve host" in m || "failed to connect" in m || "timeout" in m ||
                "network" in m || "no address associated" in m ->
                fragment.getString(R.string.account_err_no_network)
            else -> raw ?: fragment.getString(R.string.account_err_generic)
        }
    }
}
