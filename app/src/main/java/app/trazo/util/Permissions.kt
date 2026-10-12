package app.trazo.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

object Permissions {
    fun has(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun fineLocation(ctx: Context) = has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    fun anyLocation(ctx: Context) = fineLocation(ctx) || has(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
    fun backgroundLocation(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || has(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    fun activityRecognition(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || has(ctx, Manifest.permission.ACTIVITY_RECOGNITION)
    fun notifications(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || has(ctx, Manifest.permission.POST_NOTIFICATIONS)

    fun ignoringBatteryOptimizations(ctx: Context): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

    fun locationServicesEnabled(ctx: Context): Boolean {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    fun gpsEnabled(ctx: Context): Boolean =
        (ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isProviderEnabled(LocationManager.GPS_PROVIDER)

    fun appSettingsIntent(ctx: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Asks Android to exempt Trazo from battery optimisation so background trips are not cut off. */
    @android.annotation.SuppressLint("BatteryLife")
    fun batteryOptimizationIntent(ctx: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun locationSettingsIntent() = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
