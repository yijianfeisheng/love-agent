package com.zhulao.elder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlin.math.abs

class SOSActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var gestureDetector: GestureDetector
    private val contacts = mutableListOf<Pair<String, String>>() // Name, Number
    private var currentContactIndex = 0
    private var isCancelled = false
    private val handler = Handler(Looper.getMainLooper())
    private var telephonyManager: TelephonyManager? = null
    private var isCallActive = false
    private var isWaitingForIdle = false
    
    // 超时控制
    private val CALL_TIMEOUT_MS = 30000L // 30秒
    private val timeoutRunnable = Runnable {
        if (isCallActive) {
            // 超时未接通（仍在通话状态，假设无人接听）
            // 尝试挂断
            val hungUp = CallManager.endCall(this)
            if (!hungUp) {
                // 如果无法挂断，只能强制跳过，但这会导致前一个电话还在响
                // 这种情况下，我们假设用户会手动挂断，或者系统会自动挂断
            }
            // 挂断操作会触发 PhoneStateListener -> IDLE，从而触发 callNext
        }
    }

    private var countdownTimer: android.os.CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sos)
        tvStatus = findViewById(R.id.tvStatus)

        // 初始化手势识别（上滑取消）
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 != null && e1.y - e2.y > 100 && abs(velocityY) > 100) {
                    cancelSOS()
                    return true
                }
                return false
            }
        })

        loadContacts()
        startSOSLoop()
    }

    private fun cancelSOS() {
        if (isCancelled) return
        
        // 立即挂断电话
        CallManager.endCall(this@SOSActivity)
        // 停止倒计时
        countdownTimer?.cancel()
        // 标记取消
        isCancelled = true
        // 弹出确认对话框
        showCancelDialog()
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        event?.let { gestureDetector.onTouchEvent(it) }
        return super.onTouchEvent(event)
    }

    private fun loadContacts() {
        val list = ContactManager.getContacts(this)
        contacts.clear()
        for (c in list) {
            contacts.add(c.name to c.phone)
        }
        
        // 如果没有预设联系人，提示并退出
        if (contacts.isEmpty()) {
            tvStatus.text = "未设置紧急联系人"
            handler.postDelayed({ finish() }, 3000)
        }
    }

    private fun startSOSLoop() {
        if (contacts.isEmpty()) return
        
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telephonyManager?.listen(object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
                    isCallActive = true
                    isWaitingForIdle = true
                    // 开始30秒倒计时
                    handler.postDelayed(timeoutRunnable, CALL_TIMEOUT_MS)
                } else if (state == TelephonyManager.CALL_STATE_IDLE) {
                    if (isWaitingForIdle) {
                        // 电话挂断
                        isWaitingForIdle = false
                        isCallActive = false
                        // 取消倒计时
                        handler.removeCallbacks(timeoutRunnable)
                        
                        // 延迟1秒检查通话记录，确保系统已写入
                        handler.postDelayed({
                            if (!isCancelled) {
                                if (wasCallConnected()) {
                                    tvStatus.text = "通话已接通，SOS结束"
                                    handler.postDelayed({ finish() }, 2000)
                                } else {
                                    // 启动倒计时，给用户取消的机会
                                    startNextCallCountdown()
                                }
                            }
                        }, 1000)
                    }
                }
            }
        }, PhoneStateListener.LISTEN_CALL_STATE)

        callNext()
    }

    @SuppressLint("Range")
    private fun wasCallConnected(): Boolean {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return false // 没权限无法判断，只能假设未接通，继续拨打
        }
        try {
            val cursor = contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                null,
                "${android.provider.CallLog.Calls.TYPE} = ?",
                arrayOf(android.provider.CallLog.Calls.OUTGOING_TYPE.toString()),
                "${android.provider.CallLog.Calls.DATE} DESC LIMIT 1"
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val duration = it.getLong(it.getColumnIndex(android.provider.CallLog.Calls.DURATION))
                    // 如果通话时长大于0，说明接通了
                    return duration > 0
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun startNextCallCountdown() {
        // 尝试将 Activity 移至前台
        val intent = Intent(this, SOSActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)

        val nextContact = contacts[(currentContactIndex + 1) % contacts.size]
        
        countdownTimer = object : android.os.CountDownTimer(5000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                if (isCancelled) {
                    cancel()
                    return
                }
                tvStatus.text = "无人接听\n${millisUntilFinished / 1000}秒后呼叫：${nextContact.first}\n\n上滑屏幕取消呼叫"
            }

            override fun onFinish() {
                if (!isCancelled) {
                    currentContactIndex = (currentContactIndex + 1) % contacts.size
                    callNext()
                }
            }
        }.start()
    }

    private fun callNext() {
        if (isCancelled) return

        val contact = contacts[currentContactIndex]
        tvStatus.text = "正在呼叫：${contact.first}\n${contact.second}"
        
        makeCall(contact.second)
    }

    private fun makeCall(number: String) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
            startActivity(intent)
        } else {
            tvStatus.text = "缺少拨号权限"
        }
    }

    private fun showCancelDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("取消 SOS")
            .setMessage("确定要停止紧急呼叫吗？")
            .setPositiveButton("确定取消") { _, _ ->
                isCancelled = true
                finish()
            }
            .setNegativeButton("继续呼叫") { _, _ ->
                isCancelled = false
                callNext()
            }
            .setCancelable(false) // 必须手动选择
            .create()
        
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(resources.getColor(android.R.color.holo_blue_light, null))
    }

    override fun onDestroy() {
        super.onDestroy()
        isCancelled = true
        handler.removeCallbacksAndMessages(null)
        telephonyManager?.listen(null, PhoneStateListener.LISTEN_NONE)
    }
}
