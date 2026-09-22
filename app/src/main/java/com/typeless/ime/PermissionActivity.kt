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
 * Transparent trampoline activity.
 * Due to Android framework constraints, an InputMethodService is a background system service
 * and cannot directly invoke ActivityCompat.requestPermissions dialogs.
 * 
 * This activity launches invisibly in the foreground without animations, requests microphone permission,
 * dispatches the result callback to the IME service, and immediately finishes itself.
 */
class PermissionActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_RECORD_AUDIO_CODE = 1001
        private var onPermissionResultCallback: ((Boolean) -> Unit)? = null

        /**
         * Initiates microphone permission request from the IME or any Context.
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
        // Disable window enter animation
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
