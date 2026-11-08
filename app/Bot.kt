package com.example.autobot

import android.accessibilityservice.AccessibilityService
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(60, 60, 60, 60)
        }

        val btn1 = Button(this).apply {
            text = "1. Grant Floating Permission"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    Toast.makeText(this@MainActivity, "Permission already granted!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btn2 = Button(this).apply {
            text = "2. Turn On Accessibility"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val btnStart = Button(this).apply {
            text = "START FLOATING BOT"
            setBackgroundColor(Color.parseColor("#28A745"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                startService(Intent(this@MainActivity, FloatingWidgetService::class.java))
                finish()
            }
        }

        layout.addView(btn1)
        layout.addView(btn2)
        layout.addView(btnStart)
        setContentView(layout)
    }
}

class AutoPasteService : AccessibilityService() {
    companion object { var instance: AutoPasteService? = null }
    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun fillCredentials(number: String, pass: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val fields = mutableListOf<AccessibilityNodeInfo>()
        findInputs(root, fields)

        if (fields.size >= 2) {
            setText(fields[0], number)
            setText(fields[1], pass)
            return true
        } else if (fields.size == 1) {
            setText(fields[0], number)
            return true
        }
        return false
    }

    fun tapContinue(): Boolean {
        val root = rootInActiveWindow ?: return false
        val words = listOf("Continue", "Next", "Submit", "Log In", "Login")
        for (w in words) {
            for (node in root.findAccessibilityNodeInfosByText(w)) {
                if (node.isClickable) { node.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true }
                var p = node.parent
                while (p != null) {
                    if (p.isClickable) { p.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true }
                    p = p.parent
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

    private fun setText(node: AccessibilityNodeInfo, text: String) {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}

class FloatingWidgetService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var floatView: LinearLayout

    // ================= YOUR ACCOUNTS HERE =================
    private val accounts = listOf(
        Pair("08011223344", "Password123!"),
        Pair("08055667788", "Password456@"),
        Pair("09099887766", "Password789#")
    )
    // ======================================================
    private var index = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100; y = 200
        }

        floatView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DD000000"))
            setPadding(25, 25, 25, 25)
        }

        val tvStatus = TextView(this).apply { text = "Ready [0/${accounts.size}]"; setTextColor(Color.CYAN) }
        val btnNext = Button(this).apply { text = "PASTE & TAP"; setBackgroundColor(Color.BLUE); setTextColor(Color.WHITE) }
        val btnClose = Button(this).apply { text = "X"; setBackgroundColor(Color.RED); setTextColor(Color.WHITE) }

        btnNext.setOnClickListener {
            val service = AutoPasteService.instance
            if (service == null) { tvStatus.text = "Enable Accessibility!"; return@setOnClickListener }

            if (index < accounts.size) {
                val (num, pass) = accounts[index]
                tvStatus.text = "Filling: $num"
                service.fillCredentials(num, pass)

                Handler(Looper.getMainLooper()).postDelayed({
                    service.tapContinue()
                    index++
                    if (index < accounts.size) tvStatus.text = "Next: ${accounts[index].first}"
                    else tvStatus.text = "All Finished!"
                }, 400)
            } else {
                tvStatus.text = "All accounts finished!"
            }
        }

        btnClose.setOnClickListener { stopSelf() }

        floatView.addView(tvStatus)
        floatView.addView(btnNext)
        floatView.addView(btnClose)

        floatView.setOnTouchListener(object : View.OnTouchListener {
            var ix = 0; var iy = 0; var tx = 0f; var ty = 0f
            override fun onTouch(v: View?, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { ix = params.x; iy = params.y; tx = e.rawX; ty = e.rawY; return true }
                    MotionEvent.ACTION_MOVE -> { params.x = ix + (e.rawX - tx).toInt(); params.y = iy + (e.rawY - ty).toInt(); wm.updateViewLayout(floatView, params); return true }
                }
                return false
            }
        })

        wm.addView(floatView, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::floatView.isInitialized) wm.removeView(floatView)
    }
}