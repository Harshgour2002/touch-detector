package com.example.touch_detector

import android.app.AlertDialog
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Build.VERSION.SDK_INT
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.VerifiedInputEvent
import android.view.VerifiedMotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.runtime.mutableStateOf
import com.example.touch_detector.ui.theme.TouchdetectorTheme
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    companion object {
        var telemetryReport = mutableStateOf("Tap anywhere on the screen to get report")
        private const val TAG_SYNC = "SYNC_TS"          // grep this tag against getevent/logcat timestamps
        private const val TAG_DUMP = "LIVE_DUMPSYS"      // live OplusInputReader / dumpsys snapshot
    }

    private var activeDialog: AlertDialog? = null
    private var isDialogShown = false
    private var downTime = 0L
    private var maxPressure = 0f
    private var minPressure = 2.0f
    private var maxToolMajor = 0f
    private var minToolMajor = Float.MAX_VALUE
    private var maxToolMinor = 0f
    private var minToolMinor = Float.MAX_VALUE

    // Suggestion #4: Hardware Digitizer Batching & Polling Rate Tracking
    private var totalHistorySamples = 0
    private var maxHistoryBatchSize = 0
    private var lastEventTimeMs = 0L
    private var sumHistoryDeltaMs = 0.0
    private var historyDeltaCount = 0
    private var minHistoryDeltaMs = Double.MAX_VALUE
    private var maxHistoryDeltaMs = 0.0

    private lateinit var inputManager: InputManager

    // Off the UI thread so shelling out never blocks touch dispatch.
    private val bgExecutor = Executors.newSingleThreadExecutor()

    // Human-readable wall clock + monotonic clocks logged together so you can
    // line this up against `adb logcat -v time` or `adb shell getevent -lt`
    // captured on a separate terminal during the same tap.
    private val wallClockFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        inputManager = getSystemService(InputManager::class.java)

        setContent {
            TouchdetectorTheme() {
                Log.d("Touch details", "${telemetryReport.value}")
                TouchInspectorScreen(reportText = telemetryReport.value)
            }
        }
    }



    @RequiresApi(Build.VERSION_CODES.R)
    private fun isTouchReroutedByOS(event: MotionEvent): Boolean {
        val device = event.device

        // 1. Cryptographic Kernel Verification (API 30+ / Android 11+)
        // verifyInputEvent was added in API 30 (R), not API 33 (TIRAMISU).
        val verifiedEvent = if (SDK_INT >= Build.VERSION_CODES.R) {
            inputManager.verifyInputEvent(event)
        } else null

        val isUnverified = (SDK_INT >= Build.VERSION_CODES.R) && (verifiedEvent == null)

        // 2. Hardware Descriptor Check
        val hasNoHardwareDescriptor = device?.descriptor.isNullOrBlank()

        // 3. Virtual Device Identification (Cleaned precedence)
        val isVirtualDevice = (device?.isVirtual == true) || (event.deviceId <= 0)

        // 4. Window Overlay/Interception Flag
        val isObscured = (event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0

        Log.d("TOUCH_PROVENANCE", """
        === TOUCH REROUTE CONFIRMATION ===
        1. HMAC Signature Valid: ${verifiedEvent != null} (Unverified: $isUnverified)
        2. Device Name: ${device?.name}
        3. Device Descriptor: '${device?.descriptor}'
        4. Device ID: ${event.deviceId} (isVirtual: $isVirtualDevice)
        5. Obscured Flag Set: $isObscured
        ===================================
    """.trimIndent())

        return isUnverified || isVirtualDevice || hasNoHardwareDescriptor
    }
    /**
     * Emits every clock you'd want to correlate against an external capture:
     * - wall clock (matches adb logcat's default timestamp format)
     * - uptimeMillis (matches MotionEvent.eventTime's clock base)
     * - nanoTime (highest resolution, monotonic, for sub-ms ordering)
     * - event.eventTime itself, so you can compute the dispatch-to-log latency
     */
    private fun logSyncPoint(label: String, event: MotionEvent) {
        val now = System.currentTimeMillis()
        Log.d(
            TAG_SYNC,
            "label=$label wallClock=${wallClockFmt.format(Date(now))} " +
                    "uptimeMillis=${SystemClock.uptimeMillis()} " +
                    "nanoTime=${System.nanoTime()} " +
                    "event.eventTime=${event.eventTime} " +
                    "event.downTime=${event.downTime} " +
                    "deviceId=${event.deviceId} " +
                    "action=${event.actionMasked}"
        )
    }

    /**
     * Fires `dumpsys input` right at the moment of the event and grabs just the
     * OplusInputReader / InputDispatcher-relevant lines, so you catch the
     * transient MotionState instead of whatever it's reset to by the time you
     * type the command by hand in a shell.
     *
     * NOTE: on most non-rooted devices a regular app process cannot invoke
     * `dumpsys` (it requires shell/system privileges) and this will throw a
     * SecurityException/IOException — that failure is itself informative
     * (confirms you need root or an adb-shell-side capture instead), so it's
     * logged rather than swallowed silently.
     */
    private fun captureLiveDumpsysSnapshot(tag: String) {
        bgExecutor.execute {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("dumpsys", "input"))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val relevant = StringBuilder()
                var line: String?
                var capturing = false
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (l.contains("OplusInputReader") ||
                        l.contains("InputFilterEnabled") ||
                        l.contains("Gesture Monitor") ||
                        l.contains("MotionState")
                    ) {
                        capturing = true
                    }
                    if (capturing) {
                        relevant.appendLine(l)
                        // Stop once we've captured a reasonable window past the last hit
                        if (relevant.lines().size > 40) break
                    }
                }
                reader.close()
                process.destroy()
                Log.d(TAG_DUMP, "[$tag] snapshot at nanoTime=${System.nanoTime()}\n$relevant")
            } catch (e: Exception) {
                // Expected on most non-rooted devices; still logged for confirmation.
                Log.e(TAG_DUMP, "[$tag] could not shell out to dumpsys (needs shell/root): ${e.message}")
            }
        }
    }
    @RequiresApi(Build.VERSION_CODES.R)
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {

        // CALL IT HERE: Inspect provenance immediately upon entry
        val isRerouted = isTouchReroutedByOS(event)
        Log.d("TouchDetector", "Is event rerouted by OS: $isRerouted")

        try {
            val verified = inputManager.verifyInputEvent(event)
            verified?.javaClass?.methods?.forEach {
                Log.d("METHOD", it.name)
            }
            VerifiedInputEvent::class.java.declaredFields.forEach { it.isAccessible = true }
            VerifiedMotionEvent::class.java.declaredFields.forEach { it.isAccessible = true }
        } catch (e: SecurityException) {
            Log.e("VERIFY", "No permission", e)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = event.downTime
                maxPressure = event.pressure
                minPressure = event.pressure
                val tm = event.toolMajor
                val tmn = event.toolMinor
                maxToolMajor = tm
                minToolMajor = tm
                maxToolMinor = tmn
                minToolMinor = tmn

                totalHistorySamples = 0
                maxHistoryBatchSize = event.historySize
                lastEventTimeMs = event.eventTime
                sumHistoryDeltaMs = 0.0
                historyDeltaCount = 0
                minHistoryDeltaMs = Double.MAX_VALUE
                maxHistoryDeltaMs = 0.0

                processHistoricalPoints(event)
                logSyncPoint("ACTION_DOWN", event)
                captureLiveDumpsysSnapshot("ACTION_DOWN")
                generateGlobalTelemetryReport(event, 0L, maxPressure, minPressure)
            }

            MotionEvent.ACTION_MOVE -> {
                maxPressure = maxOf(maxPressure, event.pressure)
                minPressure = minOf(minPressure, event.pressure)
                val tm = event.toolMajor
                val tmn = event.toolMinor
                maxToolMajor = maxOf(maxToolMajor, tm)
                minToolMajor = minOf(minToolMajor, tm)
                maxToolMinor = maxOf(maxToolMinor, tmn)
                minToolMinor = minOf(minToolMinor, tmn)

                processHistoricalPoints(event)
                generateGlobalTelemetryReport(event, event.eventTime - downTime, maxPressure, minPressure)
            }

            MotionEvent.ACTION_UP -> {
                processHistoricalPoints(event)
                logSyncPoint("ACTION_UP", event)
                captureLiveDumpsysSnapshot("ACTION_UP")

                val duration = event.eventTime - downTime
                val isHuman = generateGlobalTelemetryReport(
                    event = event,
                    duration = duration,
                    maxP = maxPressure,
                    minP = minPressure
                )

                if (isHuman) {
                    Log.d("TouchDetector", "Human touch accepted")
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun processHistoricalPoints(event: MotionEvent) {
        val hSize = event.historySize
        maxHistoryBatchSize = maxOf(maxHistoryBatchSize, hSize)
        totalHistorySamples += hSize

        if (hSize > 0) {
            var prevTime = if (historyDeltaCount > 0) lastEventTimeMs else event.getHistoricalEventTime(0)
            for (i in 0 until hSize) {
                val histTime = event.getHistoricalEventTime(i)
                val delta = (histTime - prevTime).toDouble()
                if (delta > 0) {
                    sumHistoryDeltaMs += delta
                    historyDeltaCount++
                    minHistoryDeltaMs = minOf(minHistoryDeltaMs, delta)
                    maxHistoryDeltaMs = maxOf(maxHistoryDeltaMs, delta)
                }
                prevTime = histTime
            }
            lastEventTimeMs = event.eventTime
            val finalDelta = (event.eventTime - prevTime).toDouble()
            if (finalDelta > 0) {
                sumHistoryDeltaMs += finalDelta
                historyDeltaCount++
                minHistoryDeltaMs = minOf(minHistoryDeltaMs, finalDelta)
                maxHistoryDeltaMs = maxOf(maxHistoryDeltaMs, finalDelta)
            }
        } else {
            if (lastEventTimeMs > 0L) {
                val delta = (event.eventTime - lastEventTimeMs).toDouble()
                if (delta > 0) {
                    sumHistoryDeltaMs += delta
                    historyDeltaCount++
                    minHistoryDeltaMs = minOf(minHistoryDeltaMs, delta)
                    maxHistoryDeltaMs = maxOf(maxHistoryDeltaMs, delta)
                }
            }
            lastEventTimeMs = event.eventTime
        }
    }

    private fun generateGlobalTelemetryReport(
        event: MotionEvent,
        duration: Long,
        maxP: Float,
        minP: Float
    ): Boolean {

        Log.d("Manager1", inputManager.toString())

        for (id in inputManager.inputDeviceIds) {
            val device = inputManager.getInputDevice(id)
            Log.d(
                "Manager2",
                """
        ID: ${device?.id}
        Name: ${device?.name}
        Descriptor: ${device?.descriptor}
        Sources: 0x${device?.sources?.toString(16)}
        """.trimIndent()
            )
        }

        val toolType = if (event.pointerCount > 0)
            event.getToolType(0)
        else
            MotionEvent.TOOL_TYPE_UNKNOWN

        val toolTypeStr = when (toolType) {
            MotionEvent.TOOL_TYPE_FINGER -> "FINGER (Human)"
            MotionEvent.TOOL_TYPE_MOUSE -> "MOUSE/EMULATOR"
            MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS/PEN"
            MotionEvent.TOOL_TYPE_ERASER -> "ERASER"
            else -> "UNKNOWN"
        }

        val isObscured = (event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0
        val deviceName = event.device?.name ?: "Unknown Device"
        val device = event.device
        val inputSourceName = decodeInputSource(event.source)
        val inputSourceHex = "0x${event.source.toUInt().toString(16).uppercase()}"
        val isTouch = (event.source and InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
        val isMouse = (event.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE

        // Raw event flags in hex — useful to check for FLAG_WINDOW_IS_OBSCURED,
        // FLAG_TAINTED, or any other bit ColorOS might be setting that stock
        // AOSP devices don't, without guessing which bit matters ahead of time.
        Log.d(
            "data2",
            "isTouch=$isTouch deviceId=${event.deviceId} isVirtual=${device?.isVirtual} " +
                    "toolType(finger)=${MotionEvent.TOOL_TYPE_FINGER} singlePointer=${event.pointerCount == 1} " +
                    "obscured=$isObscured rawFlagsHex=0x${event.flags.toUInt().toString(16)}"
        )

        val isHumanTouch =
            isTouch && event.deviceId > 0 && device?.isVirtual == false &&
                    toolType == MotionEvent.TOOL_TYPE_FINGER &&
                    event.pointerCount == 1 &&
                    !isObscured

        val isMouseClick =
            isMouse &&
                    event.deviceId > 0 &&
                    device?.isVirtual == false &&
                    toolType == MotionEvent.TOOL_TYPE_MOUSE &&
                    event.pointerCount == 1 &&
                    !isObscured

        val touchMajor: Float = event.touchMajor
        val touchMinor: Float = event.touchMinor
        val toolMajor: Float = event.toolMajor
        val toolMinor: Float = event.toolMinor
        val pressure: Float = event.pressure
        val size: Float = event.size
        val orientation: Float = event.orientation
        val x: Float = event.x
        val y: Float = event.y
        val rawX: Float = event.rawX
        val rawY: Float = event.rawY
        val isRerouted = if (SDK_INT >= Build.VERSION_CODES.R) isTouchReroutedByOS(event) else false

        val rawFlagsHex = "0x${event.flags.toUInt().toString(16).uppercase()}"
        val isPartiallyObscured = if (SDK_INT >= Build.VERSION_CODES.Q) (event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0 else false
        val edgeFlagsHex = "0x${event.edgeFlags.toUInt().toString(16).uppercase()}"
        val historySize = event.historySize
        val classificationStr = if (SDK_INT >= Build.VERSION_CODES.Q) decodeClassification(event.classification) else "N/A"
        val vendorIdHex = device?.vendorId?.let { "0x${it.toUInt().toString(16).uppercase()}" } ?: "N/A"
        val productIdHex = device?.productId?.let { "0x${it.toUInt().toString(16).uppercase()}" } ?: "N/A"
        val isExternal = if (SDK_INT >= Build.VERSION_CODES.Q) (device?.isExternal ?: false) else "N/A"
        val pointerId = if (event.pointerCount > 0) event.getPointerId(0) else -1
        val buttonStateHex = "0x${event.buttonState.toUInt().toString(16).uppercase()}"
        val xPrecision = event.xPrecision
        val yPrecision = event.yPrecision
        val distance = event.getAxisValue(MotionEvent.AXIS_DISTANCE)
        val tilt = event.getAxisValue(MotionEvent.AXIS_TILT)

        var verifiedDeviceIdStr = "N/A"
        var verifiedSourceStr = "N/A"
        var verifiedActionStr = "N/A"
        var verifiedRawPosStr = "N/A"
        var verifiedTimeNanosStr = "N/A"
        var isHmacValid = false

        if (SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val verified = inputManager.verifyInputEvent(event)
                if (verified != null) {
                    isHmacValid = true
                    verifiedDeviceIdStr = verified.deviceId.toString()
                    verifiedSourceStr = "0x${verified.source.toUInt().toString(16).uppercase()}"
                    verifiedTimeNanosStr = "${verified.eventTimeNanos} ns"
                    if (verified is VerifiedMotionEvent) {
                        verifiedActionStr = MotionEvent.actionToString(verified.actionMasked)
                        verifiedRawPosStr = "(${verified.rawX}, ${verified.rawY})"
                    }
                }
            } catch (_: Exception) {}
        }

        val avgIntervalMs = if (historyDeltaCount > 0) sumHistoryDeltaMs / historyDeltaCount else 0.0
        val calculatedHz = if (avgIntervalMs > 0.0) 1000.0 / avgIntervalMs else 0.0
        val minIntervalStr = if (minHistoryDeltaMs != Double.MAX_VALUE) "%.2f ms".format(minHistoryDeltaMs) else "N/A"
        val maxIntervalStr = if (maxHistoryDeltaMs > 0.0) "%.2f ms".format(maxHistoryDeltaMs) else "N/A"
        val avgIntervalStr = if (avgIntervalMs > 0.0) "%.2f ms".format(avgIntervalMs) else "N/A"
        val calculatedHzStr = if (calculatedHz > 0.0) "%.1f Hz".format(calculatedHz) else "N/A"
        val hasHardwareBatching = maxHistoryBatchSize > 0 || totalHistorySamples > 0 || calculatedHz > 30.0

        val reportText = """
            === TOUCH EVENT TELEMETRY ===
            Action: ${MotionEvent.actionToString(event.actionMasked)} (Index: ${event.actionIndex})
            
            [ Cryptographic Kernel HMAC Signature (verifyInputEvent) ]
            • HMAC Signature Status: ${if (isHmacValid) "VALID (Kernel Signed Physical Touch)" else if (SDK_INT < Build.VERSION_CODES.R) "N/A (Requires Android 11+ / API 30)" else "INVALID / UNVERIFIED (Injected / Filtered)"}
            • Verified Device ID: $verifiedDeviceIdStr
            • Verified Source: $verifiedSourceStr
            • Verified Action Masked: $verifiedActionStr
            • Verified Raw Location (X, Y): $verifiedRawPosStr
            • Verified Event Time Nanos: $verifiedTimeNanosStr
            
            [ Touch Dimensions ]
            • Touch Major: $touchMajor
            • Touch Minor: $touchMinor
            • Touch Size: $size
            
            [ Tool Dimensions ]
            • Current Tool Major: $toolMajor
            • Min Tool Major: $minToolMajor
            • Max Tool Major: $maxToolMajor
            • Current Tool Minor: $toolMinor
            • Min Tool Minor: $minToolMinor
            • Max Tool Minor: $maxToolMinor
            
            [ Pressure, Distance & Tilt ]
            • Current Pressure: $pressure
            • Min Pressure: $minP
            • Max Pressure: $maxP
            • Distance (Hover/Stylus): $distance
            • Tilt (Stylus): $tilt
            
            [ Orientation, Position & Precision ]
            • Orientation: $orientation rad
            • Location (X, Y): ($x, $y)
            • Raw Location (X, Y): ($rawX, $rawY)
            • Motion Precision (X, Y): ($xPrecision, $yPrecision)
            
            [ Tool & Device Parameters ]
            • Tool Type: $toolTypeStr ($toolType)
            • Pointer ID: $pointerId
            • Device ID: ${event.deviceId}
            • Device Name: $deviceName
            • Is Virtual Device: ${device?.isVirtual ?: true}
            • Vendor / Product ID: $vendorIdHex / $productIdHex
            • Is External Device: $isExternal
            
            [ Source, Flags & Decision Metrics ]
            • Input Source: $inputSourceName ($inputSourceHex)
            • Pointer Count: ${event.pointerCount}
            • Button State: $buttonStateHex
            • Raw Event Flags: $rawFlagsHex
            • Window Fully Obscured: $isObscured
            • Window Partially Obscured: $isPartiallyObscured
            • Edge Flags: $edgeFlagsHex
            • OS Rerouted / Unverified: $isRerouted
            • Is Human Touch: $isHumanTouch
            • Decision: ${if (isHumanTouch) "ACCEPT (Human Touch)" else if (isMouseClick) "ACCEPT (Mouse Click)" else "REJECT (Virtual/Automated)"}
            
            [ Hardware Digitizer Batching & Polling Rate (Suggestion 4) ]
            • Current Batch History Size: $historySize
            • Max Single-Batch History Size: $maxHistoryBatchSize
            • Total Accumulated History Samples: $totalHistorySamples
            • Min Polling Interval: $minIntervalStr
            • Max Polling Interval: $maxIntervalStr
            • Avg Polling Interval: $avgIntervalStr
            • Calculated Sampling Frequency: $calculatedHzStr
            • Hardware Batching Verified: ${if (hasHardwareBatching) "YES (Physical Touchscreen Hardware)" else "NO (Single-Point / Software Injection)"}
            
            [ Gesture History & Classification ]
            • Move History Batch Size: $historySize
            • Event Classification: $classificationStr
            
            [ Timing Details ]
            • Down Time: ${event.downTime} ms (${event.downTime / 1000.0}s)
            • Event Time: ${event.eventTime} ms (Kernel when: ${event.eventTime / 1000.0}s)
            • Duration: ${duration} ms
        """.trimIndent()

        telemetryReport.value = reportText

        val validation = TouchValidationResult(
            source = event.source,
            sourceName = inputSourceName,
            sourceHex = inputSourceHex,
            deviceId = event.deviceId,
            deviceName = deviceName,
            toolType = toolType,
            toolTypeName = toolTypeStr,
            pointerCount = event.pointerCount,
            windowObscured = isObscured,
            isHumanTouch = isHumanTouch,
            isVirtual = device?.isVirtual ?: true
        )

        Log.d("INPUT", validation.toString())

        if (event.actionMasked == MotionEvent.ACTION_UP) {
            when {
                isMouseClick -> {
                    runOnUiThread {
//                        showDialog("A mouse click has been detected.")
                        Toast.makeText(this, "Clicked", Toast.LENGTH_SHORT).show()
                    }
                }

                !isHumanTouch -> {
                    runOnUiThread {
//                        showDialog(
//                            "A virtual click or synthetic touch source was detected. " +
//                                    "Automated inputs, scripts, and auto-clickers are disabled for security reasons."
//                        )
                        Toast.makeText(this, "Clicked", Toast.LENGTH_SHORT).show()
                    }
                }
                else -> Toast.makeText(this, "Clicked", Toast.LENGTH_SHORT).show()
            }
        }

        return isHumanTouch || isMouseClick
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun decodeClassification(classification: Int): String {
        return when (classification) {
            MotionEvent.CLASSIFICATION_NONE -> "NONE"
            MotionEvent.CLASSIFICATION_AMBIGUOUS_GESTURE -> "AMBIGUOUS_GESTURE"
            MotionEvent.CLASSIFICATION_DEEP_PRESS -> "DEEP_PRESS"
            MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE -> "TWO_FINGER_SWIPE"
            MotionEvent.CLASSIFICATION_PINCH -> "PINCH"
            else -> "CODE ($classification)"
        }
    }

    private fun decodeInputSource(source: Int): String {
        val sources = mutableListOf<String>()

        if (source and InputDevice.SOURCE_CLASS_BUTTON == InputDevice.SOURCE_CLASS_BUTTON)
            sources.add("CLASS_BUTTON")
        if (source and InputDevice.SOURCE_CLASS_POINTER == InputDevice.SOURCE_CLASS_POINTER)
            sources.add("CLASS_POINTER")
        if (source and InputDevice.SOURCE_CLASS_POSITION == InputDevice.SOURCE_CLASS_POSITION)
            sources.add("CLASS_POSITION")
        if (source and InputDevice.SOURCE_CLASS_JOYSTICK == InputDevice.SOURCE_CLASS_JOYSTICK)
            sources.add("CLASS_JOYSTICK")
        if (source and InputDevice.SOURCE_CLASS_TRACKBALL == InputDevice.SOURCE_CLASS_TRACKBALL)
            sources.add("CLASS_TRACKBALL")
        if (source and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD)
            sources.add("KEYBOARD")
        if (source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD)
            sources.add("DPAD")
        if (source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD)
            sources.add("GAMEPAD")
        if (source and InputDevice.SOURCE_TOUCHSCREEN == InputDevice.SOURCE_TOUCHSCREEN)
            sources.add("TOUCHSCREEN")
        if (source and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE)
            sources.add("MOUSE")
        if (source and InputDevice.SOURCE_STYLUS == InputDevice.SOURCE_STYLUS)
            sources.add("STYLUS")
        if (source and InputDevice.SOURCE_BLUETOOTH_STYLUS == InputDevice.SOURCE_BLUETOOTH_STYLUS)
            sources.add("BLUETOOTH_STYLUS")
        if (source and InputDevice.SOURCE_TOUCHPAD == InputDevice.SOURCE_TOUCHPAD)
            sources.add("TOUCHPAD")
        if (source and InputDevice.SOURCE_TRACKBALL == InputDevice.SOURCE_TRACKBALL)
            sources.add("TRACKBALL")
        if (source and InputDevice.SOURCE_MOUSE_RELATIVE == InputDevice.SOURCE_MOUSE_RELATIVE)
            sources.add("MOUSE_RELATIVE")
        if (source and InputDevice.SOURCE_ROTARY_ENCODER == InputDevice.SOURCE_ROTARY_ENCODER)
            sources.add("ROTARY_ENCODER")
        if (source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
            sources.add("JOYSTICK")
        if (source and InputDevice.SOURCE_HDMI == InputDevice.SOURCE_HDMI)
            sources.add("HDMI")
        if (source and InputDevice.SOURCE_SENSOR == InputDevice.SOURCE_SENSOR)
            sources.add("SENSOR")
        if (source and InputDevice.SOURCE_TOUCH_NAVIGATION == InputDevice.SOURCE_TOUCH_NAVIGATION)
            sources.add("TOUCH_NAVIGATION")
        if (source and InputDevice.SOURCE_UNKNOWN == InputDevice.SOURCE_UNKNOWN)
            sources.add("UNKNOWN")

        return if (sources.isEmpty()) "UNKNOWN" else sources.joinToString(" | ")
    }

    fun showDialog(message: String) {
        if (isDialogShown) return
        isDialogShown = true
        activeDialog = AlertDialog.Builder(this)
            .setTitle("Invalid Input Source")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                isDialogShown = false
                activeDialog = null
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        bgExecutor.shutdown()
    }
}


