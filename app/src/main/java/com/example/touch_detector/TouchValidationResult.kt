package com.example.touch_detector

data class TouchValidationResult(
    val source: Int,
    val sourceName: String,
    val sourceHex: String,
    val deviceId: Int,
    val deviceName: String,
    val toolType: Int,
    val toolTypeName: String,
    val pointerCount: Int,
    val windowObscured: Boolean,
    val isHumanTouch: Boolean,
    val isVirtual: Boolean
)
