package se.kartkriget

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#071521"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true // så att pågående parti sparas
        web.webViewClient = WebViewClient()
        setContentView(web)

        // Spelets fil saknar sidhuvud, så vi lägger till ett här
        val body = assets.open("index.html").bufferedReader().use { it.readText() }
        val page = """
            <!doctype html><html lang="sv"><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
            <style>[hidden]{display:none!important} body{margin:0} img{max-width:100%}</style>
            </head><body>
        """.trimIndent() + body + "</body></html>"

        // En fast adress gör att webbläsarlagringen (sparat spel) behålls mellan starter
        web.loadDataWithBaseURL("https://kartkriget.local/", page, "text/html", "utf-8", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Bakåtknappen öppnar spelets meny i stället för att stänga appen direkt
        web.evaluateJavascript(
            "(function(){var o=[...document.querySelectorAll('.overlay')].filter(e=>!e.hidden);" +
                "if(o.length){o[o.length-1].hidden=true;return 'closed';}" +
                "document.getElementById('menuBtn').click();return 'menu';})()",
            null,
        )
    }
}
