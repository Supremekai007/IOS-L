package app.ioshome.launcher

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs

/**
 * iOS-style Control Center. Only controls Android really lets a launcher change are real switches
 * (flashlight, volume, media keys, and brightness / rotation lock once the user grants "Modify system
 * settings"). Wi-Fi, Bluetooth, mobile data, dark mode and battery saver cannot be toggled by apps on
 * modern Android, so those tiles open Android's own screen instead of pretending to switch.
 */
class ControlCenterView(context: Context, private val host: LauncherActivity) : FrameLayout(context) {

    private class Tile(val root: LinearLayout, val glyph: TextView, val bg: GradientDrawable) {
        fun setActive(on: Boolean) {
            bg.setColor(if (on) Color.argb(235, 255, 255, 255) else Color.argb(70, 255, 255, 255))
            glyph.setTextColor(if (on) Color.rgb(30, 30, 40) else Color.WHITE)
        }
    }

    var isOpen: Boolean = false
        private set

    private val audio: AudioManager = context.getSystemService(AudioManager::class.java)
    private val cameraManager: CameraManager? = context.getSystemService(CameraManager::class.java)
    private val power: PowerManager? = context.getSystemService(PowerManager::class.java)
    private val uiHandler = Handler(Looper.getMainLooper())
    private var torchId: String? = null
    private var torchOn = false
    private var torchCallback: CameraManager.TorchCallback? = null
    private var downX = 0f
    private var downY = 0f

    private val panel = LinearLayout(context)
    private val scroll = ScrollView(context)
    private val content = LinearLayout(context)

    private val airplaneTile = makeTile("\u2708\uFE0F", "Airplane") {
        launchSystem(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
    }
    private val wifiTile = makeTile("\uD83D\uDCF6", "Wi-Fi") { launchSystem(internetIntent()) }
    private val bluetoothTile = makeTile("\uD83C\uDFA7", "Bluetooth") {
        launchSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }
    private val dataTile = makeTile("\uD83D\uDCF1", "Mobile data") { launchSystem(internetIntent()) }
    private val flashTile = makeTile("\uD83D\uDD26", "Flashlight") { toggleTorch() }
    private val rotationTile = makeTile("\uD83D\uDD12", "Rotation lock") { toggleRotationLock() }
    private val darkTile = makeTile("\uD83C\uDF19", "Dark mode") {
        launchSystem(Intent(Settings.ACTION_DISPLAY_SETTINGS))
    }
    private val batteryTile = makeTile("\uD83D\uDD0B", "Battery saver") {
        launchSystem(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
    }
    private val prevTile = makeTile("\u23EE\uFE0F", "Previous") { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
    private val playTile = makeTile("\u23EF\uFE0F", "Play / Pause") { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
    private val nextTile = makeTile("\u23ED\uFE0F", "Next") { sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }
    private val cameraTile = makeTile("\uD83D\uDCF7", "Camera") {
        launchSystem(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
    }
    private val settingsTile = makeTile("\u2699\uFE0F", "Settings") { launchSystem(Intent(Settings.ACTION_SETTINGS)) }
    private val launcherTile = makeTile("\u2630", "Home screen") {
        hide(false)
        host.onOpenSettings()
    }

    private val brightnessBar = SeekBar(context)
    private val volumeBar = SeekBar(context)

    init {
        visibility = View.GONE
        setBackgroundColor(Color.argb(150, 0, 0, 0))
        isClickable = true
        setOnClickListener { hide(true) }

        content.orientation = LinearLayout.VERTICAL
        content.addView(row(airplaneTile, wifiTile, bluetoothTile, dataTile), spaced(14))
        content.addView(row(flashTile, rotationTile, darkTile, batteryTile), spaced(14))

        val brightnessRow = sliderRow("\u2600\uFE0F", brightnessBar)
        brightnessRow.setOnClickListener {
            if (!Settings.System.canWrite(this.context)) askWriteSettings()
        }
        content.addView(brightnessRow, spaced(10))
        content.addView(sliderRow("\uD83D\uDD0A", volumeBar), spaced(14))

        content.addView(row(prevTile, playTile, nextTile), spaced(14))
        content.addView(row(cameraTile, settingsTile, launcherTile), spaced(10))

        val note = TextView(context)
        note.text = "Wi-Fi, Bluetooth, mobile data, dark mode and battery saver open Android's own screens, " +
            "because Android doesn't let launchers switch them directly. Brightness and rotation lock " +
            "need the \"Modify system settings\" permission, which is only requested when you use them."
        note.setTextColor(Color.argb(170, 255, 255, 255))
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        content.addView(note, spaced(0))

        scroll.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        panel.orientation = LinearLayout.VERTICAL
        panel.isClickable = true
        val bg = GradientDrawable()
        bg.setColor(Color.argb(215, 50, 50, 62))
        bg.cornerRadius = context.dpf(32f)
        panel.background = bg
        panel.setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
        )

        brightnessBar.max = 255
        brightnessBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) setBrightness(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        volumeBar.max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) setVolume(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // ---------------------------------------------------------------- building blocks

    private fun makeTile(glyphText: String, label: String, onClick: () -> Unit): Tile {
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL

        val glyph = TextView(context)
        glyph.text = glyphText
        glyph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        glyph.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.cornerRadius = context.dpf(20f)
        glyph.background = bg
        box.addView(glyph, LinearLayout.LayoutParams(context.dp(60), context.dp(60)))

        val caption = TextView(context)
        caption.text = label
        caption.setTextColor(Color.WHITE)
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        caption.gravity = Gravity.CENTER
        caption.maxLines = 1
        val captionParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        captionParams.topMargin = context.dp(4)
        box.addView(caption, captionParams)

        box.isClickable = true
        box.contentDescription = label
        box.setOnClickListener { onClick() }

        val tile = Tile(box, glyph, bg)
        tile.setActive(false)
        return tile
    }

    private fun row(vararg tiles: Tile): LinearLayout {
        val r = LinearLayout(context)
        r.orientation = LinearLayout.HORIZONTAL
        for (tile in tiles) {
            r.addView(tile.root, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return r
    }

    private fun spaced(bottomDp: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = context.dp(bottomDp)
        return lp
    }

    private fun sliderRow(glyphText: String, bar: SeekBar): LinearLayout {
        val r = LinearLayout(context)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        val bg = GradientDrawable()
        bg.setColor(Color.argb(60, 255, 255, 255))
        bg.cornerRadius = context.dpf(18f)
        r.background = bg
        r.setPadding(context.dp(14), context.dp(8), context.dp(14), context.dp(8))

        val glyph = TextView(context)
        glyph.text = glyphText
        glyph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        r.addView(
            glyph,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        val barParams = LinearLayout.LayoutParams(0, context.dp(40), 1f)
        barParams.leftMargin = context.dp(10)
        r.addView(bar, barParams)
        return r
    }

    // ---------------------------------------------------------------- show / hide

    fun show(animate: Boolean) {
        if (isOpen) return
        isOpen = true
        registerTorch()
        refresh()

        val lp = panel.layoutParams as FrameLayout.LayoutParams
        lp.width = minOf(host.availableWidth() - context.dp(24), context.dp(420))
        lp.topMargin = context.dp(8)
        panel.layoutParams = lp

        this.animate().cancel()
        panel.animate().cancel()
        visibility = View.VISIBLE
        if (animate) {
            this.alpha = 0f
            panel.translationY = -context.dpf(36f)
            this.animate().alpha(1f).setStartDelay(0L).setDuration(200L).start()
            panel.animate().translationY(0f).setStartDelay(0L).setDuration(240L)
                .setInterpolator(DecelerateInterpolator()).start()
        } else {
            this.alpha = 1f
            panel.translationY = 0f
        }
    }

    fun hide(animate: Boolean) {
        if (!isOpen) return
        isOpen = false
        unregisterTorch()
        this.animate().cancel()
        panel.animate().cancel()
        if (animate) {
            panel.animate().translationY(-context.dpf(24f)).setStartDelay(0L).setDuration(160L).start()
            this.animate().alpha(0f).setStartDelay(0L).setDuration(160L).withEndAction {
                visibility = View.GONE
                this.alpha = 1f
                panel.translationY = 0f
            }.start()
        } else {
            visibility = View.GONE
            this.alpha = 1f
            panel.translationY = 0f
        }
    }

    /** A swipe up anywhere on the overlay closes it (taps outside the panel close it too). */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
            }
            MotionEvent.ACTION_UP -> {
                val dy = ev.y - downY
                val dx = ev.x - downX
                if (isOpen && dy < -context.dp(70) && abs(dx) < abs(dy) * 0.6f) hide(true)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // ---------------------------------------------------------------- state

    /** Re-reads the real device state so the tiles never claim something Android has not applied. */
    fun refresh() {
        airplaneTile.setActive(isAirplaneOn())
        darkTile.setActive(isDark())
        batteryTile.setActive(power?.isPowerSaveMode == true)
        rotationTile.setActive(isRotationLocked())
        flashTile.setActive(torchOn)

        val canWrite = Settings.System.canWrite(context)
        brightnessBar.isEnabled = canWrite
        brightnessBar.progress = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            .coerceIn(0, 255)
        volumeBar.progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    private fun isAirplaneOn(): Boolean {
        return Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
    }

    private fun isDark(): Boolean {
        val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun isRotationLocked(): Boolean {
        return Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 0
    }

    // ---------------------------------------------------------------- actions

    private fun internetIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= 29) {
            Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
        } else {
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        }
    }

    private fun launchSystem(intent: Intent) {
        host.startSystem(intent)
    }

    private fun sendMediaKey(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun setVolume(value: Int) {
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
        } catch (e: Exception) {
            host.toast("Can't change the volume")
        }
    }

    private fun setBrightness(value: Int) {
        if (!Settings.System.canWrite(context)) return
        try {
            val resolver = context.contentResolver
            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value.coerceIn(1, 255))
        } catch (e: Exception) {
            host.toast("Can't change the brightness")
        }
    }

    private fun toggleRotationLock() {
        if (!Settings.System.canWrite(context)) {
            askWriteSettings()
            return
        }
        try {
            val locked = isRotationLocked()
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                if (locked) 1 else 0
            )
            rotationTile.setActive(isRotationLocked())
        } catch (e: Exception) {
            host.toast("Can't change rotation lock")
        }
    }

    private fun askWriteSettings() {
        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Allow changing system settings?")
            .setMessage(
                "Android requires you to grant \"Modify system settings\" to this launcher before it can " +
                    "change brightness or rotation lock. You can take it back at any time."
            )
            .setPositiveButton("Open settings") { _, _ ->
                host.startSystem(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName))
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- flashlight

    private fun findTorchId(manager: CameraManager): String? {
        try {
            for (id in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(id)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK) return id
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }

    private fun registerTorch() {
        val manager = cameraManager ?: return
        torchId = findTorchId(manager)
        flashTile.root.alpha = if (torchId == null) 0.4f else 1f
        if (torchId == null || torchCallback != null) return
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == torchId) {
                    torchOn = enabled
                    flashTile.setActive(enabled)
                }
            }
        }
        torchCallback = callback
        try {
            manager.registerTorchCallback(callback, uiHandler)
        } catch (e: Exception) {
            torchCallback = null
        }
    }

    private fun unregisterTorch() {
        val callback = torchCallback ?: return
        torchCallback = null
        try {
            cameraManager?.unregisterTorchCallback(callback)
        } catch (e: Exception) {
            // nothing to clean up
        }
    }

    private fun toggleTorch() {
        val id = torchId
        val manager = cameraManager
        if (id == null || manager == null) {
            host.toast("No flashlight available")
            return
        }
        try {
            manager.setTorchMode(id, !torchOn)
        } catch (e: Exception) {
            host.toast("Flashlight unavailable (is the camera in use?)")
        }
    }
}
