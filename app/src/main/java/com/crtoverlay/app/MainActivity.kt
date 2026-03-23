package com.crtoverlay.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.AdapterView
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.crtoverlay.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* optional */ }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            Toast.makeText(
                this,
                R.string.toast_capture_denied,
                Toast.LENGTH_LONG,
            ).show()
            updateStatus()
            return@registerForActivityResult
        }
        val svc = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_RESULT_CODE, result.resultCode)
            putExtra(OverlayService.EXTRA_PROJECTION_INTENT, result.data)
        }
        ContextCompat.startForegroundService(this, svc)
        binding.root.postDelayed({ updateStatus() }, 400)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        maybeMigrateDoubleLegacyResolution()
        loadPrefsIntoUi()
        wireSeekBars()
        wireInternalResolutionInputs()
        updateStatus()

        binding.btnPermission.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }

        binding.btnAppInfo.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                },
            )
        }

        binding.btnStart.setOnClickListener {
            savePrefsFromUi(includeInternalResolution = true)
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(
                    this,
                    R.string.toast_overlay_required,
                    Toast.LENGTH_LONG,
                ).show()
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                )
                updateStatus()
                return@setOnClickListener
            }
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
        }

        binding.btnStop.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            binding.root.postDelayed({ updateStatus() }, 200)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    /**
     * If the user still had the previous default capture size (320×240) stored, bump to the
     * current default internal resolution once.
     */
    private fun maybeMigrateDoubleLegacyResolution() {
        val p = getSharedPreferences(CrtPrefs.PREFS_NAME, MODE_PRIVATE)
        if (p.getBoolean(CrtPrefs.KEY_LEGACY_RES_DOUBLED, false)) return
        val w = if (p.contains(CrtPrefs.KEY_INTERNAL_WIDTH)) {
            p.getInt(CrtPrefs.KEY_INTERNAL_WIDTH, CrtPrefs.DEFAULT_INTERNAL_WIDTH)
        } else {
            -1
        }
        val h = if (p.contains(CrtPrefs.KEY_INTERNAL_HEIGHT)) {
            p.getInt(CrtPrefs.KEY_INTERNAL_HEIGHT, CrtPrefs.DEFAULT_INTERNAL_HEIGHT)
        } else {
            -1
        }
        p.edit {
            if (w == 320 && h == 240) {
                putInt(CrtPrefs.KEY_INTERNAL_WIDTH, CrtPrefs.DEFAULT_INTERNAL_WIDTH)
                putInt(CrtPrefs.KEY_INTERNAL_HEIGHT, CrtPrefs.DEFAULT_INTERNAL_HEIGHT)
            }
            putBoolean(CrtPrefs.KEY_LEGACY_RES_DOUBLED, true)
        }
    }

    private fun loadPrefsIntoUi() {
        val p = getSharedPreferences(CrtPrefs.PREFS_NAME, MODE_PRIVATE)
        binding.seekScanAlpha.progress = p.getInt(CrtPrefs.KEY_SCAN_ALPHA, 50).coerceIn(0, 150)
        binding.seekSpacing.progress = p.getInt(CrtPrefs.KEY_SCAN_SPACING, 5).coerceIn(1, 6)
        binding.seekVignette.progress = p.getInt(CrtPrefs.KEY_VIGNETTE, 30)
        binding.seekRgb.progress = p.getInt(CrtPrefs.KEY_RGB, 12).coerceIn(0, 100)
        binding.seekCurvature.progress = p.getInt(CrtPrefs.KEY_CURVATURE, 35)
        binding.seekBloom.progress = p.getInt(CrtPrefs.KEY_BLOOM, 18)
        binding.seekPhosphor.progress = p.getInt(CrtPrefs.KEY_PHOSPHOR_BLEED, 30)
        binding.seekColorTemp.progress = p.getInt(CrtPrefs.KEY_COLOR_TEMP, 20)
        binding.seekBlackLevel.progress = p.getInt(CrtPrefs.KEY_BLACK_LEVEL, 3)
        binding.seekHalation.progress = p.getInt(CrtPrefs.KEY_HALATION, 15)
        binding.spinnerMaskType.setSelection(p.getInt(CrtPrefs.KEY_MASK_TYPE, 0).coerceIn(0, 2))
        binding.seekGamma.progress = p.getInt(CrtPrefs.KEY_GAMMA, 22).coerceIn(18, 30) - 18
        binding.inputInternalWidth.setText(
            p.getInt(CrtPrefs.KEY_INTERNAL_WIDTH, CrtPrefs.DEFAULT_INTERNAL_WIDTH).toString(),
        )
        binding.inputInternalHeight.setText(
            p.getInt(CrtPrefs.KEY_INTERNAL_HEIGHT, CrtPrefs.DEFAULT_INTERNAL_HEIGHT).toString(),
        )
        refreshLabels()
    }

    /**
     * @param includeInternalResolution When false, only effect sliders are saved (avoids clobbering
     * width/height fields while the user is still typing).
     */
    private fun savePrefsFromUi(includeInternalResolution: Boolean = false) {
        getSharedPreferences(CrtPrefs.PREFS_NAME, MODE_PRIVATE).edit {
            putInt(CrtPrefs.KEY_SCAN_ALPHA, binding.seekScanAlpha.progress)
            putInt(CrtPrefs.KEY_SCAN_SPACING, binding.seekSpacing.progress)
            putInt(CrtPrefs.KEY_VIGNETTE, binding.seekVignette.progress)
            putInt(CrtPrefs.KEY_RGB, binding.seekRgb.progress)
            putInt(CrtPrefs.KEY_CURVATURE, binding.seekCurvature.progress)
            putInt(CrtPrefs.KEY_BLOOM, binding.seekBloom.progress)
            putInt(CrtPrefs.KEY_PHOSPHOR_BLEED, binding.seekPhosphor.progress)
            putInt(CrtPrefs.KEY_COLOR_TEMP, binding.seekColorTemp.progress)
            putInt(CrtPrefs.KEY_BLACK_LEVEL, binding.seekBlackLevel.progress)
            putInt(CrtPrefs.KEY_HALATION, binding.seekHalation.progress)
            putInt(CrtPrefs.KEY_MASK_TYPE, binding.spinnerMaskType.selectedItemPosition)
            putInt(CrtPrefs.KEY_GAMMA, binding.seekGamma.progress + 18)
            if (includeInternalResolution) {
                val w = parseAndSnapEvenWidth(binding.inputInternalWidth.text?.toString())
                val h = parseAndSnapEvenHeight(binding.inputInternalHeight.text?.toString())
                putInt(CrtPrefs.KEY_INTERNAL_WIDTH, w)
                putInt(CrtPrefs.KEY_INTERNAL_HEIGHT, h)
                binding.inputInternalWidth.setText(w.toString())
                binding.inputInternalHeight.setText(h.toString())
            }
        }
    }

    private fun parseAndSnapEvenWidth(raw: String?): Int {
        val fallback = CrtPrefs.DEFAULT_INTERNAL_WIDTH
        val v = raw?.trim()?.toIntOrNull() ?: fallback
        var x = v.coerceIn(CrtPrefs.INTERNAL_WIDTH_MIN, CrtPrefs.INTERNAL_WIDTH_MAX)
        x -= x % 2
        return x.coerceAtLeast(2)
    }

    private fun parseAndSnapEvenHeight(raw: String?): Int {
        val fallback = CrtPrefs.DEFAULT_INTERNAL_HEIGHT
        val v = raw?.trim()?.toIntOrNull() ?: fallback
        var y = v.coerceIn(CrtPrefs.INTERNAL_HEIGHT_MIN, CrtPrefs.INTERNAL_HEIGHT_MAX)
        y -= y % 2
        return y.coerceAtLeast(2)
    }

    private fun wireInternalResolutionInputs() {
        val onDone = android.view.View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                savePrefsFromUi(includeInternalResolution = true)
                refreshLabels()
                updateStatus()
            }
        }
        binding.inputInternalWidth.onFocusChangeListener = onDone
        binding.inputInternalHeight.onFocusChangeListener = onDone
    }

    private fun wireSeekBars() {
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    savePrefsFromUi(includeInternalResolution = false)
                    refreshLabels()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        binding.seekScanAlpha.setOnSeekBarChangeListener(listener)
        binding.seekSpacing.setOnSeekBarChangeListener(listener)
        binding.seekVignette.setOnSeekBarChangeListener(listener)
        binding.seekRgb.setOnSeekBarChangeListener(listener)
        binding.seekCurvature.setOnSeekBarChangeListener(listener)
        binding.seekBloom.setOnSeekBarChangeListener(listener)
        binding.seekPhosphor.setOnSeekBarChangeListener(listener)
        binding.seekColorTemp.setOnSeekBarChangeListener(listener)
        binding.seekBlackLevel.setOnSeekBarChangeListener(listener)
        binding.seekHalation.setOnSeekBarChangeListener(listener)
        binding.seekGamma.setOnSeekBarChangeListener(listener)
        binding.spinnerMaskType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                savePrefsFromUi(includeInternalResolution = false)
                refreshLabels()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun refreshLabels() {
        binding.labelScan.text = getString(R.string.scanline_strength) +
            " (${binding.seekScanAlpha.progress})"
        binding.labelSpacing.text = getString(R.string.scanline_spacing) +
            " (${binding.seekSpacing.progress})"
        binding.labelVignette.text = getString(R.string.vignette_strength) +
            " (${binding.seekVignette.progress}%)"
        binding.labelRgb.text = getString(R.string.rgb_mask_strength) +
            " (${binding.seekRgb.progress})"
        binding.labelCurvature.text = getString(R.string.curvature_strength) +
            " (${binding.seekCurvature.progress})"
        binding.labelBloom.text = getString(R.string.bloom_strength) +
            " (${binding.seekBloom.progress})"
        binding.labelPhosphor.text = getString(R.string.phosphor_bleed) +
            " (${binding.seekPhosphor.progress}%)"
        binding.labelColorTemp.text = getString(R.string.color_temp) +
            " (${binding.seekColorTemp.progress}%)"
        binding.labelBlackLevel.text = getString(R.string.black_level) +
            " (${binding.seekBlackLevel.progress})"
        binding.labelHalation.text = getString(R.string.halation_strength) +
            " (${binding.seekHalation.progress}%)"
        val gammaVal = (binding.seekGamma.progress + 18) / 10f
        binding.labelGamma.text = getString(R.string.gamma_label) +
            " ($gammaVal)"
        val maskNames = resources.getStringArray(R.array.mask_type_entries)
        val maskIdx = binding.spinnerMaskType.selectedItemPosition.coerceIn(0, maskNames.size - 1)
        binding.labelMaskType.text = getString(R.string.mask_type) +
            " (${maskNames[maskIdx]})"
        val w = parseAndSnapEvenWidth(binding.inputInternalWidth.text?.toString())
        val h = parseAndSnapEvenHeight(binding.inputInternalHeight.text?.toString())
        binding.labelInternalRes.text = getString(R.string.internal_resolution) +
            " ($w×$h)"
    }

    private fun updateStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val running = OverlayService.isRunning
        val w = parseAndSnapEvenWidth(binding.inputInternalWidth.text?.toString())
        val h = parseAndSnapEvenHeight(binding.inputInternalHeight.text?.toString())
        binding.status.text = buildString {
            append("Display over other apps: ")
            append(if (overlayOk) "allowed" else "NOT allowed — use the button above")
            append("\nEmulated CRT grid: min ")
            append(w)
            append("×")
            append(h)
            append(" (extended to fill screen)")
            append("\nOverlay: ")
            append(if (running) "ON (notification + capture active)" else "OFF")
        }
    }
}
