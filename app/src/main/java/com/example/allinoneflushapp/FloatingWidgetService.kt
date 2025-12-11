package com.example.allinoneflushapp

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.ImageView
import kotlinx.coroutines.*

class FloatingWidgetService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var bubbleImage: ImageView? = null
    
    companion object {
        private var instance: FloatingWidgetService? = null
        
        fun isRunning() = instance != null
        
        fun updateBubbleColor(isActive: Boolean) {
            instance?.updateColor(isActive)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        floatingView = LayoutInflater.from(this).inflate(R.layout.widget_layout, null)
        bubbleImage = floatingView?.findViewById(R.id.floatingBubble)
        val textCB = floatingView?.findViewById<TextView>(R.id.textCB)


        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager?.addView(floatingView, params)

        // Make draggable
        floatingView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(floatingView, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val deltaX = event.rawX - initialTouchX
                        val deltaY = event.rawY - initialTouchY
                        
                        // If small movement, treat as click
                        if (Math.abs(deltaX) < 10 && Math.abs(deltaY) < 10) {
                            openMainActivity()
                        }
                        return true
                    }
                }
                return false
            }
        })

        // Start monitoring status
        startStatusMonitor()
    }

    private fun updateColor(isActive: Boolean) {
        val from = if (isActive) R.drawable.red_circle else R.drawable.green_circle
        val to = if (isActive) R.drawable.green_circle else R.drawable.red_circle
    
        val start = ContextCompat.getColor(this, 
            if (isActive) R.color.red_smooth else R.color.green_smooth
        )
        val end = ContextCompat.getColor(this, 
            if (isActive) R.color.green_smooth else R.color.red_smooth
        )
    
        animateColorTransition(start, end)
    }

    private fun animateColorTransition(fromColor: Int, toColor: Int) {
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 500
        animator.addUpdateListener { value ->
            val fraction = value.animatedFraction
            val blended = blendColors(fromColor, toColor, fraction)
            bubbleImage?.setColorFilter(blended)
        }
        animator.start()
    }
    
    private fun blendColors(from: Int, to: Int, ratio: Float): Int {
        val inverse = 1f - ratio
        val r = Color.red(from) * inverse + Color.red(to) * ratio
        val g = Color.green(from) * inverse + Color.green(to) * ratio
        val b = Color.blue(from) * inverse + Color.blue(to) * ratio
        return Color.rgb(r.toInt(), g.toInt(), b.toInt())
    }

    private fun startStatusMonitor() {
        CoroutineScope(Dispatchers.IO).launch {
            while (isRunning()) {
                val active = AppMonitorVPNService.isPandaActive()
                withContext(Dispatchers.Main) {
                    updateColor(active)
                }
                delay(1500)
            }
        }
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
    }

    override fun onDestroy() {
        if (floatingView != null) {
            windowManager?.removeView(floatingView)
        }
        instance = null
        super.onDestroy()
    }
}
