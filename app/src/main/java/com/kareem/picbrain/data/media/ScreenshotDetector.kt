package com.kareem.picbrain.data.media

import java.util.Locale

object ScreenshotDetector {
    private val hints = listOf("screenshot", "screen_shot", "screen-shot", "screenshots", "스크린샷")

    fun isScreenshot(displayName: String?, relativePath: String?, bucketName: String?): Boolean {
        val haystack = listOfNotNull(displayName, relativePath, bucketName)
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        return hints.any(haystack::contains)
    }
}
