package com.bsinaly.echelle

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#E9E4DA")
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#E9E4DA"))
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true // sauvegarde des niveaux et des étoiles
            allowFileAccess = true
            textZoom = 100
        }
        web.webViewClient = WebViewClient()
        setContentView(web)
        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl("file:///android_asset/index.html")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Ferme d'abord une fenêtre ouverte (règles, victoire), sinon quitte
        web.evaluateJavascript(
            "(function(){var o=document.querySelector('.overlay.on');if(o){o.classList.remove('on');return 1}return 0})()"
        ) { r -> if (r != "1") finish() }
    }
}
