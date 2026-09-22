package com.typeless.ime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * PermissionActivity
 * 
 * 透明中介 Activity（Trampoline Activity）。
 * 由於 Android 系統限制，InputMethodService 是特殊系統服務，
 * 無法直接彈出 ActivityCompat.requestPermissions 授權視窗。
 * 
 * 本 Activity 會以全透明無動畫的方式在前景閃現，跳出系統麥克風授權框，
 * 待使用者點選「允許」或「拒絕」後，透過回呼通知輸入法並立即關閉自己。
 */
class PermissionActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_RECORD_AUDIO_CODE = 1001
        private var onPermissionResultCallback: ((Boolean) -> Unit)? = null

        /**
         * 從輸入法或任何 Context 啟動授權請求
         */
        fun requestRecordAudio(context: Context, onResult: (Boolean) -> Unit) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                onResult(true)
                return
            }

            onPermissionResultCallback = onResult
            val intent = Intent(context, PermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 移除進入動畫
        overridePendingTransition(0, 0)

        val isGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (isGranted) {
            notifyAndFinish(true)
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_CODE) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            notifyAndFinish(granted)
        }
    }

    private fun notifyAndFinish(granted: Boolean) {
        onPermissionResultCallback?.invoke(granted)
        onPermissionResultCallback = null
        finish()
        overridePendingTransition(0, 0)
    }
}
