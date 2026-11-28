package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanOptions
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentWarningConfigBinding
import neth.iecal.curbox.ui.activity.PortraitCaptureActivity
import java.util.UUID

internal class WarningConfigQrController(
    private val fragment: Fragment,
    private val binding: FragmentWarningConfigBinding,
    private val barcodeLauncher: ActivityResultLauncher<ScanOptions>,
    private val timingDialog: WarningUnlockTimingDialog,
    private val keyListRenderer: WarningUnlockKeyListRenderer
) {
    private val currentKeys = mutableMapOf<String, Long>()
    private var pendingDuration = -1L

    fun bind(keys: Map<String, Long>) {
        currentKeys.clear()
        currentKeys.putAll(keys)
        refreshKeyList()
    }

    fun setupListeners() {
        binding.btnGenerateQr.setOnClickListener {
            timingDialog.show(::generateQrCode)
        }
        binding.btnScanExistingQr.setOnClickListener {
            timingDialog.show(::launchBarcodeScanner)
        }
    }

    fun onBarcodeResult(contents: String?) {
        if (contents == null) {
            Toast.makeText(
                fragment.requireContext(),
                R.string.warning_scan_cancelled,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        currentKeys[contents] = pendingDuration
        refreshKeyList()
        Toast.makeText(
            fragment.requireContext(),
            R.string.warning_qr_saved,
            Toast.LENGTH_SHORT
        ).show()
    }

    fun keys(): Map<String, Long> = currentKeys

    fun refreshKeyList() {
        keyListRenderer.render(
            binding.qrListContainer,
            currentKeys,
            "QR/Barcode",
            ::refreshKeyList
        )
    }

    private fun generateQrCode(duration: Long) {
        val key = UUID.randomUUID().toString()
        currentKeys[key] = duration
        refreshKeyList()
        try {
            val bitmap = BarcodeEncoder().encodeBitmap(
                key,
                BarcodeFormat.QR_CODE,
                QR_IMAGE_SIZE,
                QR_IMAGE_SIZE
            )
            val imageView = ImageView(fragment.requireContext()).apply {
                setImageBitmap(bitmap)
                setPadding(32, 32, 32, 32)
            }
            MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(R.string.warning_qr_generated_title)
                .setMessage(R.string.warning_qr_generated_message)
                .setView(imageView)
                .setPositiveButton(R.string.done, null)
                .setNeutralButton(R.string.warning_save_to_gallery) { _, _ ->
                    saveImageToGallery(bitmap)
                }
                .show()
        } catch (exception: Exception) {
            Toast.makeText(
                fragment.requireContext(),
                R.string.warning_qr_generate_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun launchBarcodeScanner(duration: Long) {
        pendingDuration = duration
        val options = ScanOptions()
        options.setDesiredBarcodeFormats(ScanOptions.ALL_CODE_TYPES)
        options.setPrompt(fragment.getString(R.string.warning_scan_qr_code_prompt))
        options.setCameraId(0)
        options.setBeepEnabled(false)
        options.setBarcodeImageEnabled(true)
        options.setCaptureActivity(PortraitCaptureActivity::class.java)
        barcodeLauncher.launch(options)
    }

    private fun saveImageToGallery(bitmap: Bitmap) {
        val context = fragment.requireContext()
        val values = ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "Unlock_QR_${System.currentTimeMillis()}.png"
            )
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Curbox"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        )
        if (uri == null) {
            Toast.makeText(
                context,
                R.string.warning_mediastore_failed,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        try {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
            Toast.makeText(
                context,
                R.string.warning_saved_to_gallery,
                Toast.LENGTH_SHORT
            ).show()
        } catch (exception: Exception) {
            Toast.makeText(
                context,
                R.string.warning_save_image_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private companion object {
        const val QR_IMAGE_SIZE = 800
    }
}
