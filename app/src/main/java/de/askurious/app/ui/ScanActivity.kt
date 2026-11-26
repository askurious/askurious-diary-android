package de.askurious.app.ui

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.journeyapps.barcodescanner.CaptureActivity

/**
 * The QR scanner of ZXing Android Embedded, kept clear of the system bars: from Android 15 on every screen runs edge
 * to edge, and the scanner's hint ("Hold the study's QR code inside the frame") lay under the navigation bar.
 */
class ScanActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // the camera may show the QR code of a personal invitation: no picture of it in "Recents" (from Android 13;
        // before that no screenshots at all, the only way there; security audit 2026-11-26, SEC-15)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }
}
