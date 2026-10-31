package com.chronos.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.json.JSONObject;

/**
 * Activity principal do Chronos App.
 * Carrega a interface HTML/JS/CSS embutida em assets de forma offline,
 * executa em modo imersivo de tela cheia e fornece, para a interface,
 * a versão instalada do aplicativo e a abertura de links externos,
 * utilizados pelo sistema de verificação de atualizações.
 */
public class MainActivity extends AppCompatActivity {

    private WebView webView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        enableImmersiveFullscreen();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        // Permite que o som de tique-taque (AudioContext) inicie sem gesto do usuário.
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setTextZoom(100);

        // Ponte JS <--> Android usada pela verificação de atualizações.
        webView.addJavascriptInterface(new ChronosBridge(), "ChronosBridge");

        webView.setWebViewClient(new WebViewClient() {
            // Abre links externos no navegador do sistema, mantendo o
            // aplicativo sempre na interface local.
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    openExternalUrl(url);
                    return true;
                }
                return false;
            }

            // Após o carregamento, injeta a versão instalada e dispara a
            // verificação silenciosa de atualização.
            @Override
            public void onPageFinished(WebView view, String url) {
                String version = JSONObject.quote(BuildConfig.VERSION_NAME);
                view.evaluateJavascript("window.chronosAppVersion=" + version + ";", null);
                view.evaluateJavascript(
                        "if (typeof chronosCheckForUpdates === 'function') chronosCheckForUpdates(true);",
                        null);
            }
        });

        webView.setBackgroundColor(0xFF0F172A);
        webView.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void openExternalUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
            // Nenhum navegador disponível: falha silenciosa.
        }
    }

    /**
     * Oculta todas as barras do sistema (status e navegação) e define o
     * comportamento imersivo. Reaplicado em onWindowFocusChanged para que
     * as barras voltem a se ocultar após qualquer reaparição transitória.
     */
    private void enableImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enableImmersiveFullscreen();
        }
    }

    /** Métodos expostos à interface HTML como window.ChronosBridge. */
    private class ChronosBridge {

        /**
         * Abre uma URL externa no navegador do sistema.
         * Usado para levar o usuário à página de download da nova versão.
         */
        @JavascriptInterface
        public void openExternal(final String url) {
            if (url == null) {
                return;
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    openExternalUrl(url);
                }
            });
        }
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
