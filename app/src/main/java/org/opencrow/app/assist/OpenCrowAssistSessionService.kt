package org.opencrow.app.assist

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Creates the VoiceInteractionSession that receives assist data (including screenshots)
 * from the system, then delegates to AssistActivity for the Compose-based UI.
 */
class OpenCrowAssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return OpenCrowAssistSession(this)
    }
}

private class OpenCrowAssistSession(
    private val service: OpenCrowAssistSessionService
) : VoiceInteractionSession(service) {

    companion object {
        private const val TAG = "AssistSession"
    }

    private var screenshotPath: String? = null
    private var screenshotHandled = false
    private var assistHandled = false

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        if (screenshot != null) {
            try {
                val file = File(service.cacheDir, "assist_screenshot_${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { out ->
                    screenshot.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
                screenshotPath = file.absolutePath
                Log.d(TAG, "Screenshot saved: ${file.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save screenshot", e)
            }
        }
        screenshotHandled = true
        maybeStartActivity()
    }

    override fun onHandleAssist(state: AssistState) {
        assistHandled = true
        maybeStartActivity()
    }

    private fun maybeStartActivity() {
        if (!assistHandled || !screenshotHandled) return

        val intent = Intent(service, org.opencrow.app.AssistActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            screenshotPath?.let { putExtra("screenshot_path", it) }
        }
        service.startActivity(intent)
        hide()
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Reset state for each new session
        screenshotPath = null
        // If the system won't deliver a particular callback, mark it as
        // handled so we don't wait for one that will never come.
        screenshotHandled = (showFlags and SHOW_WITH_SCREENSHOT) == 0
        assistHandled = (showFlags and SHOW_WITH_ASSIST) == 0
        // If both are already handled (neither flag set), launch immediately
        maybeStartActivity()
    }
}
