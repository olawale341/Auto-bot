package com.example.autobot

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
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

// ================= SHARED DATA =================
object DataStore {
    var numbers = mutableListOf<String>()
    var singlePassword = ""
    var currentIndex = 0
    var speedMultiplier: Float = 1.0f // 0.1x to 10.0x

    fun getDelay(baseMs: Long): Long {
        val speed = speedMultiplier.coerceIn(0.1f, 10.0f)
        return (baseMs / speed).toLong().coerceAtLeast(60L)
    }
}

// ================= 1. SETUP SCREEN =================
class MainActivity : AppCompatActivity() {

    private lateinit var etPassword: EditText
    private lateinit var etNumbers: EditText
    private lateinit var tvSpeedLabel: TextView
    private lateinit var sbSpeed: SeekBar
    private val PICK_FILE_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("autobot_prefs", Context.MODE_PRIVATE)

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

        // Turbo Speed Controller (0.1x to 10.0x)
        val savedSpeed = prefs.getFloat("saved_speed", 1.0f)
        tvSpeedLabel = TextView(this).apply {
            text = "⚡ Speed: ${String.format("%.1fx", savedSpeed)}"
            setTextColor(Color.parseColor("#00FFCC"))
            textSize = 15f
            setPadding(0, 20, 0, 5)
        }

        sbSpeed = SeekBar(this).apply {
            max = 99 // 0 to 99 represents 0.1x to 10.0x
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

        // Password input
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

        // Numbers input
        val tvNumLabel = TextView(this).apply {
            text = "Numbers (one per line):"
            setTextColor(Color.LTGRAY)
            setPadding(0, 20, 0, 5)
        }
        etNumbers = EditText(this).apply {
            hint = "08011223344\n08055667788\n09099887766"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5
            gravity = Gravity.TOP
            setText(prefs.getString("saved_numbers", ""))
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#222222"))
            setTextColor(Color.WHITE)
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
                val rawNumbers = etNumbers.text.toString().lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                if (pass.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Please enter a password!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (rawNumbers.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Please add at least one number!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val speed = (sbSpeed.progress + 1) / 10f

                prefs.edit()
                    .putString("saved_pass", pass)
                    .putString("saved_numbers", etNumbers.text.toString())
                    .putFloat("saved_speed", speed)
                    .apply()

                DataStore.singlePassword = pass
                DataStore.numbers.clear()
                DataStore.numbers.addAll(rawNumbers)
                DataStore.currentIndex = 0
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
        layout.addView(tvNumLabel)
        layout.addView(etNumbers)
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
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                        etNumbers.setText(reader.readText())
                        Toast.makeText(this, "Numbers imported successfully!", Toast.LENGTH_SHORT).show()
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

    private var lastPasswordNodeBounds: Rect? = null

    fun fillCredentials(number: String, pass: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val fields = mutableListOf<AccessibilityNodeInfo>()
        findInputs(root, fields)

        if (fields.isEmpty()) return false

        if (fields.size >= 2) {
            setText(fields[0], number)
            setText(fields[1], pass)
            val rect = Rect()
            fields[1].getBoundsInScreen(rect)
            lastPasswordNodeBounds = rect
            return true
        } else if (fields.size == 1) {
            setText(fields[0], number)
            val rect = Rect()
            fields[0].getBoundsInScreen(rect)
            lastPasswordNodeBounds = rect
            return true
        }
        return false
    }

    /**
     * Tap 1: Continue/Next below the password field
     */
    fun tapContinueBelowPassword(): Boolean {
        val root = rootInActiveWindow ?: return false

        val words = listOf("Continue", "Next", "Submit", "Log In", "Login", "Sign In", "Proceed", "Done", "Go")
        for (w in words) {
            val nodes = root.findAccessibilityNodeInfosByText(w)
            for (node in nodes) {
                if (clickNodeOrParent(node)) return true
            }
        }

        val passBounds = lastPasswordNodeBounds
        if (passBounds != null) {
            val clickables = mutableListOf<AccessibilityNodeInfo>()
            findClickableNodes(root, clickables)
            val buttonBelow = clickables.filter {
                val r = Rect()
                it.getBoundsInScreen(r)
                r.top >= (passBounds.bottom - 10)
            }.minByOrNull {
                val r = Rect()
                it.getBoundsInScreen(r)
                r.top
            }

            if (buttonBelow != null && clickNodeOrParent(buttonBelow)) {
                return true
            }
        }
        return false
    }

    /**
     * Tap 2: The red Next button at bottom right
     */
    fun tapBottomRightNextButton(): Boolean {
        val root = rootInActiveWindow ?: return false
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        val targetWords = listOf("Next", "Continue", "Confirm", "Proceed", "Done", "OK")
        for (w in targetWords) {
            val nodes = root.findAccessibilityNodeInfosByText(w)
            for (node in nodes) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.top >= screenHeight * 0.45 && r.left >= screenWidth * 0.3) {
                    if (clickNodeOrParent(node)) return true
                }
            }
        }

        val clickables = mutableListOf<AccessibilityNodeInfo>()
        findClickableNodes(root, clickables)

        val bottomRightNode = clickables.filter {
            val r = Rect()
            it.getBoundsInScreen(r)
            r.top >= screenHeight * 0.5 && r.left >= screenWidth * 0.35
        }.maxByOrNull {
            val r = Rect()
            it.getBoundsInScreen(r)
            r.bottom + r.right
        }

        if (bottomRightNode != null) {
            return clickNodeOrParent(bottomRightNode)
        }
        return false
    }

    /**
     * Tap 3: Tap action beside "Login" at the top (e.g. Register / Sign Up tab)
     * Strictly targets the top 30% of screen to prevent misclicking anything else.
     */
    fun tapTopActionBesideLogin(): Boolean {
        val root = rootInActiveWindow ?: return false
        val metrics = resources.displayMetrics
        val screenHeight = metrics.heightPixels
        val topLimit = (screenHeight * 0.30).toInt()

        // 1. Direct text check for registration words in the top 30%
        val registerWords = listOf("Register", "Sign up", "Signup", "Registration", "Create account", "Join")
        for (w in registerWords) {
            val nodes = root.findAccessibilityNodeInfosByText(w)
            for (node in nodes) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.top <= topLimit) {
                    if (clickNodeOrParent(node)) return true
                }
            }
        }

        // 2. Look for "Login" at the top and find its horizontal neighbor on the same bar
        val loginWords = listOf("Login", "Log In", "Sign In", "Sign in")
        var loginRect: Rect? = null
        for (w in loginWords) {
            val nodes = root.findAccessibilityNodeInfosByText(w)
            for (node in nodes) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.top <= topLimit) {
                    loginRect = r
                    break
                }
            }
            if (loginRect != null) break
        }

        if (loginRect != null) {
            val clickables = mutableListOf<AccessibilityNodeInfo>()
            findClickableNodes(root, clickables)

            val neighbor = clickables.filter {
                val r = Rect()
                it.getBoundsInScreen(r)
                r.top <= topLimit &&
                Math.abs(r.centerY() - loginRect.centerY()) <= 110 &&
                !r.contains(loginRect.centerX(), loginRect.centerY()) &&
                r.width() > 15 && r.height() > 15
            }.minByOrNull {
                val r = Rect()
                it.getBoundsInScreen(r)
                Math.abs(r.centerX() - loginRect.centerX())
            }

            if (neighbor != null && clickNodeOrParent(neighbor)) {
                return true
            }
        }

        return false
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        var p = node.parent
        while (p != null) {
            if (p.isClickable) {
                p.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
            p = p.parent
        }
        return false
    }

    private fun findInputs(node: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.isEditable) list.add(node)
        for (i in 0 until node.childCount) findInputs(node.getChild(i), list)
    }

    private fun findClickableNodes(node: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.isClickable) list.add(node)
        for (i in 0 until node.childCount) findClickableNodes(node.getChild(i), list)
    }

    private fun setText(node: AccessibilityNodeInfo, text: String) {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}

// ================= 3. COMPACT FLOATING WIDGET (AUTO-LOOP) =================
class FloatingWidgetService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var floatView: LinearLayout
    private lateinit var btnPlay: Button
    private lateinit var tvStatus: TextView

    private var isPlaying = false
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
            setPadding(18, 10, 18, 10)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE1A1A24"))
                cornerRadius = 40f
                setStroke(2, Color.parseColor("#445577"))
            }
        }

        val total = DataStore.numbers.size
        tvStatus = TextView(this).apply {
            text = "[${DataStore.currentIndex}/$total]"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 12f
            setPadding(6, 0, 10, 0)
        }

        btnPlay = Button(this).apply {
            text = "▶ PLAY"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#28A745"))
            setPadding(16, 4, 16, 4)
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
                btnPlay.text = "⏸ PAUSE"
                btnPlay.setBackgroundColor(Color.parseColor("#E67E22"))
                runAutoStep()
            } else {
                stopAutoLoop("Paused")
            }
        }

        btnClose.setOnClickListener {
            stopAutoLoop("Stopped")
            stopSelf()
        }

        floatView.addView(tvStatus)
        floatView.addView(btnPlay)
        floatView.addView(btnClose)

        floatView.setOnTouchListener(object : View.OnTouchListener {
            var ix = 0; var iy = 0; var tx = 0f; var ty = 0f
            override fun onTouch(v: View?, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        ix = params.x; iy = params.y
                        tx = e.rawX; ty = e.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = ix + (e.rawX - tx).toInt()
                        params.y = iy + (e.rawY - ty).toInt()
                        wm.updateViewLayout(floatView, params)
                        return true
                    }
                }
                return false
            }
        })

        wm.addView(floatView, params)
    }

    /**
     * Complete Turbo-Scaled Sequence:
     * 1. Fill credentials
     * 2. Tap Next below password
     * 3. Tap Red Next at bottom-right
     * 4. Tap action beside Login at the top (Register/Tab)
     * 5. Wait for new boxes -> Next number!
     */
    private fun runAutoStep() {
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

        // Step 1: Fill Credentials
        val filled = service.fillCredentials(currentNum, currentPass)

        if (filled) {
            tvStatus.text = "[${DataStore.currentIndex + 1}/$total]"

            // Step 2: Tap 1st Next below password
            loopHandler.postDelayed({
                if (!isPlaying) return@postDelayed
                service.tapContinueBelowPassword()

                tvStatus.text = "Red Next..."

                // Step 3: Tap red Next at bottom-right
                loopHandler.postDelayed({
                    if (!isPlaying) return@postDelayed
                    service.tapBottomRightNextButton()

                    tvStatus.text = "Top Action..."

                    // Step 4: Tap action beside Login at the top (Register)
                    loopHandler.postDelayed({
                        if (!isPlaying) return@postDelayed
                        service.tapTopActionBesideLogin()

                        DataStore.currentIndex++

                        // Step 5: Wait for fresh screen to load, then repeat
                        loopHandler.postDelayed({
                            if (isPlaying) runAutoStep()
                        }, DataStore.getDelay(1600))

                    }, DataStore.getDelay(1200))

                }, DataStore.getDelay(1300))

            }, DataStore.getDelay(350))

        } else {
            // Screen loading; poll with scaled delay
            tvStatus.text = "Waiting..."
            loopHandler.postDelayed({
                if (isPlaying) runAutoStep()
            }, DataStore.getDelay(700))
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
