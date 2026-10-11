# R8 rules of the app. The libraries bring their own (WorkManager keeps its workers, ZXing its activity).

# The end page of a questionnaire calls AskuriousApp.done() through the WebView bridge (EntryActivity.Bridge):
# R8 sees no caller, so the class and its @JavascriptInterface methods must keep their names.
-keepattributes JavascriptInterface
-keep class de.askurious.app.ui.EntryActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
