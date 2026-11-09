package com.example.autobot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

// ================= SHARED DATA =================
object DataStore {
    var numbers = mutableListOf<String>()
    var singlePassword = ""
    var currentIndex = 0
    var speedMultiplier: Float = 1.0f

    fun getStepDelay(): Long {
        val speed = speedMultiplier.coerceIn(0.1f, 10.0f)
        return (600L / speed).toLong().coerceIn(80L, 3500L)
    }

    fun formatTo10Digits(raw: String): String {
        var s = raw.filter { it.isDigit() }
        if (s.startsWith("234") && s.length >= 13) {
            s = s.substring(3)
        }
        if (s.startsWith("0") && s.length == 11) {
            s = s.substring(1)
        }
        return s
    }

    fun saveNumbersToFile(context: Context, list: List<String>) {
        val cleaned = list.map { formatTo10Digits(it) }.filter { it.isNotEmpty() }
        val file = File(context.filesDir, "numbers_data.txt")
        file.writeText(cleaned.joinToString("\n"))
        numbers.clear()
        numbers.addAll(cleaned)
    }

    fun loadNumbersFromFile(context: Context): List<String> {
        val file = File(context.filesDir, "numbers_data.txt")
        return if (file.exists()) {
            file.readLines().map { formatTo10Digits(it) }.filter { it.isNotEmpty() }
        } else emptyList()
    }
}

// ================= 1. SETUP SCREEN =================
class MainActivity : AppCompatActivity() {

    private lateinit var etPassword: EditText
    private lateinit var etStartIndex: EditText
    private lateinit var tvFileStatus: TextView
    private lateinit var tvSpeedLabel: TextView
    private lateinit var sbSpeed: SeekBar
    private val PICK_FILE_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("autobot_prefs", Context.MODE_PRIVATE)

        val savedList = DataStore.loadNumbersFromFile(this)
        if (savedList.isNotEmpty()) {
            DataStore.numbers.clear()
            DataStore.numbers.addAll(savedList)
        }

        val rootScroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        rootScroll.addView(layout)

        val tvTitle = TextView(this).apply {
            text = "AutoBot Setup"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 20)
        }

        val btnOverlay = Button(this).apply {
            text = "1. Grant Floating Permission"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    Toast.makeText(this@MainActivity, "Overlay already enabled!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnAccess = Button(this).apply {
            text = "2. Turn On Accessibility"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val savedSpeed = prefs.getFloat("saved_speed", 1.0f)
        tvSpeedLabel = TextView(this).apply {
            text = "⚡ Speed: ${String.format("%.1fx", savedSpeed)}"
            setTextColor(Color.parseColor("#00FFCC"))
            textSize = 15f
            setPadding(0, 20, 0, 5)
        }

        sbSpeed = SeekBar(this).apply {
            max = 99
            progress = ((savedSpeed * 10f) - 1f).toInt().coerceIn(0, 99)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    val spd = (prog + 1) / 10f
                    val mode = when {
                        spd < 1.0f -> "Slow"
                        spd == 1.0f -> "Normal"
                        spd < 4.0f -> "Fast"
                        else -> "TURBO ⚡"
                    }
                    tvSpeedLabel.text = "⚡ Speed: ${String.format("%.1fx", spd)} ($mode)"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        val tvPassLabel = TextView(this).apply {
            text = "Single Password (for all numbers):"
            setTextColor(Color.LTGRAY)
            setPadding(0, 20, 0, 5)
        }
        etPassword = EditText(this).apply {
            hint = "Enter password"
            setText(prefs.getString("saved_pass", ""))
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#222222"))
            setTextColor(Color.WHITE)
        }

        val tvJumpLabel = TextView(this).apply {
            text = "Start from number # (Skip already done):"
            setTextColor(Color.parseColor("#FFCC00"))
            setPadding(0, 20, 0, 5)
        }
        etStartIndex = EditText(this).apply {
            hint = "e.g., 1 (or 51 to start from 51st)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("saved_start_index", 1).toString())
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#222222"))
            setTextColor(Color.WHITE)
        }

        tvFileStatus = TextView(this).apply {
            text = if (DataStore.numbers.isNotEmpty()) "📁 Storage: ${DataStore.numbers.size} numbers ready (10 digits)" else "No file imported yet"
            setTextColor(if (DataStore.numbers.isNotEmpty()) Color.parseColor("#00FFCC") else Color.GRAY)
            textSize = 14f
            setPadding(0, 15, 0, 5)
        }

        val btnPickFile = Button(this).apply {
            text = "📁 Import Numbers From .txt File"
            setBackgroundColor(Color.parseColor("#334455"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "text/*" }
                startActivityForResult(intent, PICK_FILE_CODE)
            }
        }

        val btnStart = Button(this).apply {
            text = "▶ START FLOATING BOT"
            setBackgroundColor(Color.parseColor("#28A745"))
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 25, 0, 25)
            setOnClickListener {
                val pass = etPassword.text.toString().trim()
                if (pass.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Please enter a password!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                if (DataStore.numbers.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Please import a .txt file first!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val startNum = etStartIndex.text.toString().toIntOrNull() ?: 1
                val targetIndex = (startNum - 1).coerceIn(0, DataStore.numbers.size - 1)
                val speed = (sbSpeed.progress + 1) / 10f

                prefs.edit()
                    .putString("saved_pass", pass)
                    .putInt("saved_start_index", startNum)
                    .putFloat("saved_speed", speed)
                    .apply()

                DataStore.singlePassword = pass
                DataStore.currentIndex = targetIndex
                DataStore.speedMultiplier = speed

                startService(Intent(this@MainActivity, FloatingWidgetService::class.java))
                finish()
            }
        }

        layout.addView(tvTitle)
        layout.addView(btnOverlay)
        layout.addView(btnAccess)
        layout.addView(tvSpeedLabel)
        layout.addView(sbSpeed)
        layout.addView(tvPassLabel)
        layout.addView(etPassword)
        layout.addView(tvJumpLabel)
        layout.addView(etStartIndex)
        layout.addView(tvFileStatus)
        layout.addView(btnPickFile)
        layout.addView(View(this).apply { minimumHeight = 25 })
        layout.addView(btnStart)

        setContentView(rootScroll)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FILE_CODE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    val list = mutableListOf<String>()
                    contentResolver.openInputStream(uri)?.bufferedReader()?.useLines { lines ->
                        lines.forEach { line ->
                            val clean = DataStore.formatTo10Digits(line.trim())
                            if (clean.isNotEmpty()) list.add(clean)
                        }
                    }

                    if (list.isNotEmpty()) {
                        DataStore.saveNumbersToFile(this, list)
                        tvFileStatus.text = "✅ Loaded ${list.size} numbers (10 digits each)!"
                        tvFileStatus.setTextColor(Color.parseColor("#00FFCC"))
                        Toast.makeText(this, "Imported ${list.size} numbers successfully!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "File was empty!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to read file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

// ================= 2. AUTO-PASTE & TAP ENGINE =================
class AutoPasteService : AccessibilityService() {
    companion object { var instance: AutoPasteService? = null }
    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun tapCoordinates(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 150)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return dispatchGesture(gesture, null, null)
        }
        return false
    }

    fun tapNodeDirect(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            tapCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        var p = node.parent
        for (i in 0..3) {
            if (p != null) {
                p.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                p = p.parent
            } else break
        }
        return true
    }

    /**
     * Checks strictly if the +234 Phone and Password input boxes are open on screen
     */
    fun areInputBoxesOpen(): Boolean {
        val root = rootInActiveWindow ?: return false
        val fields = mutableListOf<AccessibilityNodeInfo>()
        findInputs(root, fields)
        return fields.size >= 2
    }

    /**
     * STEP 1: Tap REGISTER at the top beside LOGIN
     */
    fun tapRegister(): Boolean {
        val root = rootInActiveWindow ?: return false
        val screenHeight = resources.displayMetrics.heightPixels
        val topLimit = (screenHeight * 0.35).toInt()

        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        findAllNodes(root, allNodes)

        // 1. Direct search for text "REGISTER" at the top
        for (node in allNodes) {
            val text = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
            if (text.equals("REGISTER", ignoreCase = true) || text.contains("REGISTER", ignoreCase = true)) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.top <= topLimit && r.bottom > 0) {
                    tapNodeDirect(node)
                    return true
                }
            }
        }

        // 2. Direct fallback: Find "LOGIN" and tap right beside it (where REGISTER sits)
        for (node in allNodes) {
            val text = (node.text?.toString() ?: "").trim()
            if (text.equals("LOGIN", ignoreCase = true)) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.top <= topLimit && r.bottom > 0) {
                    val regX = r.right + (r.width() * 0.9f)
                    val regY = r.centerY().toFloat()
                    tapCoordinates(regX, regY)
                    return true
                }
            }
        }
        return false
    }

    /**
     * STEP 2: Paste 10-digit number into Phone and password into Password
     */
    fun fillCredentials(number: String, pass: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val fields = mutableListOf<AccessibilityNodeInfo>()
        findInputs(root, fields)

        if (fields.size < 2) return false

        setText(fields[0], DataStore.formatTo10Digits(number))
        setText(fields[1], pass)
        return true
    }

    /**
     * STEP 3: Tap Yellow NEXT
     */
    fun tapYellowNext(): Boolean {
        val root = rootInActiveWindow ?: return false
        val screenWidth = resources.displayMetrics.widthPixels
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        findAllNodes(root, allNodes)

        for (node in allNodes) {
            val t = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
            if (t.equals("NEXT", ignoreCase = true)) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    tapCoordinates(r.centerX().toFloat(), r.centerY().toFloat())
                    tapCoordinates(screenWidth * 0.5f, r.centerY().toFloat())
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            }
        }
        return false
    }

    /**
     * STEP 4: Tap Red 4.NEXT
     */
    fun tapRedNext(): Boolean {
        val root = rootInActiveWindow ?: return false
        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels

        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        findAllNodes(root, allNodes)

        for (node in allNodes) {
            val t = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
            if (t.contains("4.NEXT", ignoreCase = true) || t.equals("NEXT", ignoreCase = true)) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.top >= screenHeight * 0.45 && r.left >= screenWidth * 0.25) {
                    return tapNodeDirect(node)
                }
            }
        }
        return false
    }

    private fun findInputs(node: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.isEditable) list.add(node)
        for (i in 0 until node.childCount) findInputs(node.getChild(i), list)
    }

    private fun findAllNodes(node: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        list.add(node)
        for (i in 0 until node.childCount) findAllNodes(node.getChild(i), list)
    }

    private fun setText(node: AccessibilityNodeInfo, text: String) {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}

// ================= 3. COMPACT FLOATING WIDGET =================
enum class StepState {
    STEP_1_TAP_REGISTER,
    STEP_2_PASTE_CREDENTIALS,
    STEP_3_TAP_YELLOW_NEXT,
    STEP_4_TAP_RED_NEXT
}

class FloatingWidgetService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var floatView: LinearLayout
    private lateinit var btnPlay: Button
    private lateinit var tvStatus: TextView

    private var isPlaying = false
    private var currentState = StepState.STEP_1_TAP_REGISTER
    private val loopHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 250
        }

        floatView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 8, 14, 8)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE1A1A24"))
                cornerRadius = 40f
                setStroke(2, Color.parseColor("#445577"))
            }
        }

        val tvDragGrip = TextView(this).apply {
            text = "⠿"
            textSize = 18f
            setTextColor(Color.parseColor("#8899AA"))
            setPadding(8, 0, 8, 0)
        }

        val total = DataStore.numbers.size
        tvStatus = TextView(this).apply {
            text = "[${DataStore.currentIndex + 1}/$total]"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 12f
            setPadding(4, 0, 10, 0)
        }

        btnPlay = Button(this).apply {
            text = "▶ PLAY"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#28A745"))
            setPadding(14, 4, 14, 4)
            minHeight = 0
            minimumHeight = 0
        }

        val btnClose = Button(this).apply {
            text = "✕"
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(10, 4, 6, 4)
            minHeight = 0
            minimumHeight = 0
        }

        btnPlay.setOnClickListener {
            if (!isPlaying) {
                isPlaying = true
                currentState = StepState.STEP_1_TAP_REGISTER
                btnPlay.text = "⏸ PAUSE"
                btnPlay.setBackgroundColor(Color.parseColor("#E67E22"))
                runVerifiedSequence()
            } else {
                stopAutoLoop("Paused")
            }
        }

        btnClose.setOnClickListener {
            stopAutoLoop("Stopped")
            stopSelf()
        }

        floatView.addView(tvDragGrip)
        floatView.addView(tvStatus)
        floatView.addView(btnPlay)
        floatView.addView(btnClose)

        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f
        val dragListener = View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    ix = params.x; iy = params.y
                    tx = event.rawX; ty = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = ix + (event.rawX - tx).toInt()
                    params.y = iy + (event.rawY - ty).toInt()
                    wm.updateViewLayout(floatView, params)
                    true
                }
                else -> false
            }
        }
        tvDragGrip.setOnTouchListener(dragListener)

        wm.addView(floatView, params)
    }

    /**
     * Perfect Step-by-Step Sequence:
     * Step 1: Tap REGISTER (repeats until input boxes actually open!)
     * Step 2: Pastes 10-digit number & password
     * Step 3: Taps Yellow NEXT (waits until input boxes close!)
     * Step 4: Taps Red NEXT, advances index, loops back to Step 1!
     */
    private fun runVerifiedSequence() {
        if (!isPlaying) return

        val total = DataStore.numbers.size
        if (DataStore.currentIndex >= total) {
            tvStatus.text = "All Done!"
            stopAutoLoop("Finished")
            return
        }

        val service = AutoPasteService.instance
        if (service == null) {
            tvStatus.text = "Enable Accessibility!"
            stopAutoLoop("Error")
            return
        }

        val currentNum = DataStore.numbers[DataStore.currentIndex]
        val currentPass = DataStore.singlePassword
        val numIndex = "[${DataStore.currentIndex + 1}/$total]"

        when (currentState) {

            // STEP 1: Tap REGISTER (NEVER skipped unless +234 Phone is literally open)
            StepState.STEP_1_TAP_REGISTER -> {
                tvStatus.text = "$numIndex 1.Reg"

                if (service.areInputBoxesOpen()) {
                    currentState = StepState.STEP_2_PASTE_CREDENTIALS
                    loopHandler.postDelayed({ if (isPlaying) runVerifiedSequence() }, 150)
                    return
                }

                service.tapRegister()

                loopHandler.postDelayed({
                    if (service.areInputBoxesOpen()) {
                        currentState = StepState.STEP_2_PASTE_CREDENTIALS
                    }
                    if (isPlaying) runVerifiedSequence()
                }, DataStore.getStepDelay())
            }

            // STEP 2: Paste credentials (strictly 10 digits)
            StepState.STEP_2_PASTE_CREDENTIALS -> {
                tvStatus.text = "$numIndex 2.Paste"
                val filled = service.fillCredentials(currentNum, currentPass)
                if (filled) {
                    currentState = StepState.STEP_3_TAP_YELLOW_NEXT
                }
                loopHandler.postDelayed({ if (isPlaying) runVerifiedSequence() }, DataStore.getStepDelay())
            }

            // STEP 3: Tap Yellow NEXT (Stays here until boxes close!)
            StepState.STEP_3_TAP_YELLOW_NEXT -> {
                tvStatus.text = "$numIndex 3.Yellow"

                // If input boxes have closed, Yellow NEXT has officially submitted!
                if (!service.areInputBoxesOpen()) {
                    currentState = StepState.STEP_4_TAP_RED_NEXT
                    loopHandler.postDelayed({ if (isPlaying) runVerifiedSequence() }, 200)
                    return
                }

                service.tapYellowNext()

                loopHandler.postDelayed({
                    if (!service.areInputBoxesOpen()) {
                        currentState = StepState.STEP_4_TAP_RED_NEXT
                    }
                    if (isPlaying) runVerifiedSequence()
                }, DataStore.getStepDelay())
            }

            // STEP 4: Tap Red NEXT, then loop back to Step 1!
            StepState.STEP_4_TAP_RED_NEXT -> {
                tvStatus.text = "$numIndex 4.Red"
                val tapped = service.tapRedNext()
                if (tapped) {
                    DataStore.currentIndex++
                    currentState = StepState.STEP_1_TAP_REGISTER // Always loops back to Step 1!
                }
                loopHandler.postDelayed({ if (isPlaying) runVerifiedSequence() }, DataStore.getStepDelay())
            }
        }
    }

    private fun stopAutoLoop(statusMsg: String) {
        isPlaying = false
        btnPlay.text = "▶ PLAY"
        btnPlay.setBackgroundColor(Color.parseColor("#28A745"))
        if (statusMsg.isNotEmpty()) tvStatus.text = statusMsg
    }

    override fun onDestroy() {
        super.onDestroy()
        isPlaying = false
        loopHandler.removeCallbacksAndMessages(null)
        if (::floatView.isInitialized) wm.removeView(floatView)
    }
}