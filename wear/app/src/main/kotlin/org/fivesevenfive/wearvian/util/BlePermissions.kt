package org.fivesevenfive.wearvian.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** The Bluetooth runtime permissions the key needs to scan for and connect to the vehicle. */
object BlePermissions {

    val REQUIRED = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    fun granted(context: Context): Boolean = REQUIRED.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** For an ActivityResult grant map: true iff every required permission was granted (now or already). */
    fun granted(context: Context, grants: Map<String, Boolean>): Boolean =
        REQUIRED.all { grants[it] == true || ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
}
