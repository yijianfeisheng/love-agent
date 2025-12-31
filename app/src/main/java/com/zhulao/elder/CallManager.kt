package com.zhulao.elder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat

object CallManager {
    
    @SuppressLint("MissingPermission")
    fun endCall(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                    tm.endCall()
                    return true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return false
        }
        
        return endCallViaReflection(context)
    }

    @SuppressLint("SoonBlockedPrivateApi")
    private fun endCallViaReflection(context: Context): Boolean {
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            val method = tm.javaClass.getDeclaredMethod("getITelephony")
            method.isAccessible = true
            val telephony = method.invoke(tm)
            
            val endCallMethod = telephony.javaClass.getDeclaredMethod("endCall")
            endCallMethod.invoke(telephony)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }
}
