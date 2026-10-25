package de.tridash

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var loaded = false
    private val perms = setOf(
        ExerciseSessionRecord::class, HeartRateRecord::class, SleepSessionRecord::class,
        RestingHeartRateRecord::class, HeartRateVariabilityRmssdRecord::class,
        DistanceRecord::class, PowerRecord::class
    ).map { HealthPermission.getReadPermission(it) }.toSet()

    private val req = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { sync() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        web = WebView(this); setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(object {
            @JavascriptInterface fun copy(t: String) = runOnUiThread {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("tri", t))
                Toast.makeText(this@MainActivity, "Kopiert", Toast.LENGTH_SHORT).show()
            }
        }, "Android")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView?, u: String?) { loaded = true; start() }
        }
        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onResume() { super.onResume(); if (loaded) start() }

    private fun start() {
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            Toast.makeText(this, "Health Connect fehlt oder ist nicht aktuell", Toast.LENGTH_LONG).show(); return
        }
        lifecycleScope.launch {
            val c = HealthConnectClient.getOrCreate(this@MainActivity)
            if (c.permissionController.getGrantedPermissions().containsAll(perms)) sync() else req.launch(perms)
        }
    }

    private fun sync() = lifecycleScope.launch {
        try {
            val c = HealthConnectClient.getOrCreate(this@MainActivity)
            val end = Instant.now(); val start = end.minus(30, ChronoUnit.DAYS)
            val zone = ZoneId.systemDefault(); val range = TimeRangeFilter.between(start, end)
            val sessions = JSONArray()
            for (r in c.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range)).records) {
                val sport = when (r.exerciseType) {
                    ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Rad"
                    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Lauf"
                    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Schwimmen"
                    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "Kraft"
                    else -> null
                } ?: continue
                val a = c.aggregate(AggregateRequest(
                    setOf(HeartRateRecord.BPM_AVG, DistanceRecord.DISTANCE_TOTAL, PowerRecord.POWER_AVG),
                    TimeRangeFilter.between(r.startTime, r.endTime)))
                sessions.put(JSONObject()
                    .put("id", r.metadata.id).put("s", sport).put("n", "HC")
                    .put("d", r.startTime.atZone(zone).toLocalDate().toString())
                    .put("m", Duration.between(r.startTime, r.endTime).toMinutes())
                    .put("h", a[HeartRateRecord.BPM_AVG] ?: JSONObject.NULL)
                    .put("w", a[PowerRecord.POWER_AVG]?.inWatts?.toLong() ?: JSONObject.NULL)
                    .put("k", a[DistanceRecord.DISTANCE_TOTAL]?.inKilometers?.let { Math.round(it * 100) / 100.0 } ?: JSONObject.NULL)
                    .put("p", JSONObject.NULL))
            }
            val days = sortedMapOf<String, JSONObject>()
            fun day(i: Instant) = days.getOrPut(i.atZone(zone).toLocalDate().toString()) { JSONObject() }
            for (r in c.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range)).records) {
                val o = day(r.endTime)
                o.put("sleepMin", o.optLong("sleepMin") + Duration.between(r.startTime, r.endTime).toMinutes())
            }
            for (r in c.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, range)).records)
                day(r.time).put("rhr", r.beatsPerMinute)
            for (r in c.readRecords(ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, range)).records)
                day(r.time).put("hrv", Math.round(r.heartRateVariabilityMillis))
            val well = JSONArray()
            days.forEach { (d, o) -> well.put(o.put("d", d)) }
            val json = JSONObject().put("sessions", sessions).put("wellness", well)
            web.evaluateJavascript("window.onHC($json)", null)
        } catch (e: Exception) {
            Toast.makeText(this@MainActivity, "Sync-Fehler: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
