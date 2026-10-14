package de.askurious.app.test

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle

/** Runs the real Activity/WebView checks with local synthetic questionnaire fixtures. */
class UiInstrumentation : Instrumentation() {
    private var phase = ""
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); phase = arguments?.getString("phase") ?: "ui-entry"; start() }
    override fun onStart() {
        try {
            UiAuditChecks.run(this, phase)
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: $phase\n") })
        } catch (failure: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAIL: $phase: ${failure.message}\n") })
        }
    }
}
