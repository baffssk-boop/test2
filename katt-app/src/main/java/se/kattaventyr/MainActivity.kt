package se.kattaventyr

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var voice: Voice

    /** Kopplar sidans uppläsning till telefonens egen talsyntes (WebView saknar webbläsarens). */
    class Voice(activity: Activity) : TextToSpeech.OnInitListener {
        private val tts = TextToSpeech(activity, this)
        private var ready = false
        private var swedish = false

        override fun onInit(status: Int) {
            if (status == TextToSpeech.SUCCESS) {
                val result = tts.setLanguage(Locale("sv", "SE"))
                swedish = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
                ready = true
            }
        }

        @JavascriptInterface
        fun speak(text: String, rate: Float) {
            if (!ready) return
            tts.setSpeechRate(rate)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "katt")
        }

        @JavascriptInterface
        fun hasSwedish(): Boolean = swedish

        fun shutdown() = tts.shutdown()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voice = Voice(this)

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#F3EEFF"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true // så att squishies och teckningar sparas
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(voice, "AndroidTTS")
        setContentView(web)

        // Sidans fil saknar sidhuvud, så vi lägger till ett här
        val body = assets.open("index.html").bufferedReader().use { it.readText() }
        val page = """
            <!doctype html><html lang="sv"><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
            <style>[hidden]{display:none!important} body{margin:0} img{max-width:100%}</style>
            </head><body>
        """.trimIndent() + body + "</body></html>"

        // En fast adress gör att sparade squishies och teckningar behålls mellan starter
        web.loadDataWithBaseURL("https://kattaventyr.local/", page, "text/html", "utf-8", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Bakåtknappen går till startsidan; på startsidan stängs appen
        web.evaluateJavascript(
            "(function(){if(!document.getElementById('home').hidden)return 'exit';show('home');return 'home';})()",
        ) { result ->
            if (result == "\"exit\"") finish()
        }
    }

    override fun onDestroy() {
        voice.shutdown()
        super.onDestroy()
    }
}
