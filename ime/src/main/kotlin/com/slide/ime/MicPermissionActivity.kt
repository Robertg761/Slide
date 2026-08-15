package com.slide.ime

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Asks for the microphone on the keyboard's behalf.
 *
 * An [android.inputmethodservice.InputMethodService] is a service, and only an activity can show a
 * runtime permission dialog. So the keyboard starts this, which is invisible, puts the system
 * dialog up, and finishes — the keyboard learns the answer by re-checking the permission, since a
 * service cannot receive an activity result.
 *
 * Once the user has denied with "don't ask again", [requestPermissions] returns immediately and
 * silently, so without more the mic key would do nothing forever. That case is detected here and
 * routed to the app's system settings page, with a toast saying why, so the user always has a
 * path to turning voice typing on.
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
        val denied = grantResults.isEmpty() ||
            grantResults[0] != PackageManager.PERMISSION_GRANTED
        // False after a denial means the system will no longer show the dialog at all: either
        // "don't ask again" was chosen or the policy stopped asking. Empty grantResults means the
        // request was interrupted; that is not a settled denial, so it does not redirect.
        if (
            denied &&
            grantResults.isNotEmpty() &&
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        ) {
            openAppSettings()
        }
        finishPermissionTask()
    }

    private fun openAppSettings() {
        Toast.makeText(
            applicationContext,
            R.string.mic_permission_denied_permanently,
            Toast.LENGTH_LONG,
        ).show()
        val settingsIntent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(settingsIntent)
        } catch (e: ActivityNotFoundException) {
            // The toast has already told the user where to go; nothing more can be done on a
            // device with no app-details settings screen.
            Log.w(TAG, "No activity handles the app settings screen", e)
        }
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
        private const val TAG = "SlideIME"

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
