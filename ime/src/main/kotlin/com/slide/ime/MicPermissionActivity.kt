package com.slide.ime

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.content.ContextCompat

/**
 * Asks for the microphone on the keyboard's behalf.
 *
 * An [android.inputmethodservice.InputMethodService] is a service, and only an activity can show a
 * runtime permission dialog. So the keyboard starts this, which is invisible, puts the system
 * dialog up, and finishes — the keyboard learns the answer by re-checking the permission, since a
 * service cannot receive an activity result.
 */
class MicPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (hasPermission(this)) {
            finishPermissionTask()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finishPermissionTask()
    }

    /**
     * The permission proxy owns a deliberately isolated, invisible task. Remove that task rather
     * than merely finishing its only activity so Android always returns to the editor that was
     * under the IME, never to Slide's launcher/settings task.
     */
    private fun finishPermissionTask() {
        finishAndRemoveTask()
    }

    companion object {
        private const val REQUEST_CODE = 1

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        /**
         * Started from a service context, hence the new task. The manifest gives the proxy no task
         * affinity, and these flags keep its transient task out of Recents and task restoration.
         */
        fun intent(context: Context): Intent =
            Intent(context, MicPermissionActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_HISTORY or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION,
                )
    }
}
