package com.chronos.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.graphics.Color;
import android.view.View;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Process;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * Activity principal do Chronos.
 * A bússola usa os sensores físicos do aparelho, sem GPS, localização ou internet.
 */
public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private static final int WEATHER_HOST_ID = 0x4348524F;
    private static final int REQUEST_BIND_WEATHER_WIDGET = 8120;
    private static final int REQUEST_CONFIGURE_WEATHER_WIDGET = 8121;
    private static final int INVALID_WIDGET_ID = AppWidgetManager.INVALID_APPWIDGET_ID;
    private static final String WEATHER_WIDGET_PREFS = "chronos_weather_widget";
    private static final String WEATHER_WIDGET_ID_KEY = "widget_id";
    private static final String[] WEATHER_PROVIDER_PACKAGES = {
            "com.rlk.weathers", "com.transsion.weather"
    };

    private WebView webView;
    private FrameLayout rootLayout;
    private AppWidgetManager appWidgetManager;
    private AppWidgetHost weatherWidgetHost;
    private AppWidgetHostView weatherWidgetView;
    private int weatherWidgetId = INVALID_WIDGET_ID;
    private int pendingWeatherWidgetId = INVALID_WIDGET_ID;
    private AppWidgetProviderInfo pendingWeatherProvider;
    private boolean weatherTabVisible = false;
    private float weatherWidgetLeft = 0f;
    private float weatherWidgetTop = 0f;
    private float weatherWidgetWidth = 0f;
    private float weatherWidgetHeight = 0f;

    private SensorManager sensorManager;
    private Sensor rotationVectorSensor;
    private Sensor accelerometerSensor;
    private Sensor magneticFieldSensor;
    private volatile boolean compassRequested = false;

    private final float[] accelerometerReading = new float[3];
    private final float[] magneticFieldReading = new float[3];
    private boolean hasAccelerometerReading = false;
    private boolean hasMagneticFieldReading = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        enableImmersiveFullscreen();

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            magneticFieldSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        }

        rootLayout = new FrameLayout(this);
        rootLayout.setClipChildren(false);
        rootLayout.setClipToPadding(false);
        appWidgetManager = AppWidgetManager.getInstance(this);
        weatherWidgetHost = new AppWidgetHost(this, WEATHER_HOST_ID);

        webView = new WebView(this);
        rootLayout.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(rootLayout);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setTextZoom(100);

        // A interface JavaScript só é adicionada depois de ativar JavaScript.
        webView.addJavascriptInterface(new CompassBridge(), "ChronosCompass");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                restoreWeatherWidgetIfAvailable();
            }
        });
        webView.setBackgroundColor(0xFF0F172A);
        webView.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void enableImmersiveFullscreen() {
        // Desenha o WebView por trás das áreas das barras do sistema.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        // Compatibilidade com versões e interfaces Android que ainda dependem
        // dos flags legados para remover a faixa vazia acima do conteúdo.
        final int immersiveFlags =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        getWindow().getDecorView().setSystemUiVisibility(immersiveFlags);

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    private boolean hasCompassSensor() {
        return rotationVectorSensor != null
                || (accelerometerSensor != null && magneticFieldSensor != null);
    }

    /**
     * Registra o sensor de rotação composto quando disponível. Em aparelhos sem ele,
     * combina acelerômetro e magnetômetro para calcular o azimute.
     * Deve ser chamado na thread principal.
     */
    private boolean registerCompassSensors() {
        if (sensorManager == null || !compassRequested) return false;

        unregisterCompassSensors();

        if (rotationVectorSensor != null) {
            return sensorManager.registerListener(
                    this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI);
        }

        if (accelerometerSensor == null || magneticFieldSensor == null) return false;

        hasAccelerometerReading = false;
        hasMagneticFieldReading = false;

        boolean accelerometerRegistered = sensorManager.registerListener(
                this, accelerometerSensor, SensorManager.SENSOR_DELAY_UI);
        boolean magneticRegistered = sensorManager.registerListener(
                this, magneticFieldSensor, SensorManager.SENSOR_DELAY_UI);

        if (!accelerometerRegistered || !magneticRegistered) {
            unregisterCompassSensors();
            return false;
        }
        return true;
    }

    private void unregisterCompassSensors() {
        if (sensorManager != null) sensorManager.unregisterListener(this);
    }

    private void sendCompassStatus(String message) {
        if (webView == null) return;
        final String safeMessage = message.replace("\\", "\\\\").replace("'", "\\'");
        webView.post(() -> webView.evaluateJavascript(
                "if (window.onChronosCompassStatus) window.onChronosCompassStatus('" +
                        safeMessage + "');", null));
    }

    private final class CompassBridge {
        @JavascriptInterface
        public boolean startCompass() {
            if (!hasCompassSensor()) return false;

            compassRequested = true;
            runOnUiThread(() -> {
                if (compassRequested && !registerCompassSensors()) {
                    sendCompassStatus("Não foi possível iniciar os sensores da bússola.");
                }
            });
            return true;
        }

        @JavascriptInterface
        public void stopCompass() {
            compassRequested = false;
            runOnUiThread(() -> unregisterCompassSensors());
        }

        /** Vincula e exibe o widget oficial do Daily Weather dentro do Chronos. */
        @JavascriptInterface
        public void connectWeatherWidget() {
            runOnUiThread(() -> beginWeatherWidgetConnection());
        }

        @JavascriptInterface
        public void setWeatherWidgetBounds(double left, double top, double width, double height) {
            weatherWidgetLeft = (float) left;
            weatherWidgetTop = (float) top;
            weatherWidgetWidth = (float) width;
            weatherWidgetHeight = (float) height;
            weatherTabVisible = true;
            runOnUiThread(() -> layoutWeatherWidget());
        }

        @JavascriptInterface
        public void setWeatherTabVisible(boolean visible) {
            weatherTabVisible = visible;
            runOnUiThread(() -> {
                if (weatherWidgetView != null) {
                    weatherWidgetView.setVisibility(visible ? View.VISIBLE : View.GONE);
                }
            });
        }
    }

    private SharedPreferences weatherPreferences() {
        return getSharedPreferences(WEATHER_WIDGET_PREFS, MODE_PRIVATE);
    }

    private void sendWeatherStatus(String message, boolean connected) {
        if (webView == null) return;
        String quoted = JSONObject.quote(message);
        webView.post(() -> webView.evaluateJavascript(
                "if (window.onChronosWeatherStatus) window.onChronosWeatherStatus(" +
                        quoted + "," + connected + ");", null));
    }

    private List<AppWidgetProviderInfo> getManufacturerWeatherProviders() {
        List<AppWidgetProviderInfo> providers = new ArrayList<>();
        for (String packageName : WEATHER_PROVIDER_PACKAGES) {
            try {
                List<AppWidgetProviderInfo> found;
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    found = appWidgetManager.getInstalledProvidersForPackage(
                            packageName, Process.myUserHandle());
                } else {
                    found = appWidgetManager.getInstalledProviders();
                }
                if (found == null) continue;
                for (AppWidgetProviderInfo info : found) {
                    if (info != null && info.provider != null
                            && packageName.equals(info.provider.getPackageName())) {
                        providers.add(info);
                    }
                }
            } catch (Exception ignored) {
                // O pacote pode não fornecer um widget instalável.
            }
            if (!providers.isEmpty()) break;
        }
        providers.sort(Comparator.comparingLong(info ->
                Math.max(1, info.minWidth) * (long) Math.max(1, info.minHeight)));
        return providers;
    }

    private boolean isManufacturerWeatherProvider(AppWidgetProviderInfo info) {
        if (info == null || info.provider == null) return false;
        String pkg = info.provider.getPackageName();
        for (String expected : WEATHER_PROVIDER_PACKAGES) {
            if (expected.equals(pkg)) return true;
        }
        return false;
    }

    private Bundle createWeatherWidgetOptions(AppWidgetProviderInfo info) {
        Bundle options = new Bundle();
        int minWidth = Math.max(180, info.minWidth);
        int minHeight = Math.max(180, info.minHeight);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, minWidth);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, Math.max(minWidth, 340));
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, minHeight);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, Math.max(minHeight, 420));
        return options;
    }

    private void beginWeatherWidgetConnection() {
        if (weatherWidgetView != null) {
            sendWeatherStatus("O widget oficial do clima já está conectado ao Chronos.", true);
            layoutWeatherWidget();
            return;
        }

        int savedId = weatherPreferences().getInt(WEATHER_WIDGET_ID_KEY, INVALID_WIDGET_ID);
        if (savedId != INVALID_WIDGET_ID) {
            AppWidgetProviderInfo savedInfo = appWidgetManager.getAppWidgetInfo(savedId);
            if (isManufacturerWeatherProvider(savedInfo)) {
                weatherWidgetId = savedId;
                attachWeatherWidget(savedId, savedInfo);
                return;
            }
            weatherPreferences().edit().remove(WEATHER_WIDGET_ID_KEY).apply();
        }

        List<AppWidgetProviderInfo> providers = getManufacturerWeatherProviders();
        if (providers.isEmpty()) {
            sendWeatherStatus(
                    "O Daily Weather não disponibilizou um widget para o Chronos neste aparelho. " +
                    "Não vou substituir a previsão por dados de outra fonte.",
                    false);
            return;
        }

        pendingWeatherProvider = providers.get(0);
        pendingWeatherWidgetId = weatherWidgetHost.allocateAppWidgetId();
        Bundle options = createWeatherWidgetOptions(pendingWeatherProvider);

        try {
            boolean allowed = appWidgetManager.bindAppWidgetIdIfAllowed(
                    pendingWeatherWidgetId, pendingWeatherProvider.provider, options);
            if (allowed) {
                finishWeatherWidgetBinding(options);
            } else {
                Intent bindIntent = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
                bindIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWeatherWidgetId);
                bindIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER,
                        pendingWeatherProvider.provider);
                bindIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options);
                sendWeatherStatus("Autorize o Chronos a exibir o widget oficial do clima nesta tela.", false);
                startActivityForResult(bindIntent, REQUEST_BIND_WEATHER_WIDGET);
            }
        } catch (Exception e) {
            clearPendingWeatherWidget();
            sendWeatherStatus("Não foi possível vincular o widget oficial do clima.", false);
        }
    }

    private void finishWeatherWidgetBinding(Bundle options) {
        if (pendingWeatherWidgetId == INVALID_WIDGET_ID || pendingWeatherProvider == null) return;
        if (pendingWeatherProvider.configure != null) {
            try {
                weatherWidgetHost.startAppWidgetConfigureActivityForResult(
                        this, pendingWeatherWidgetId, 0,
                        REQUEST_CONFIGURE_WEATHER_WIDGET, options);
                return;
            } catch (Exception ignored) {
                // Continua se a configuração for opcional ou indisponível.
            }
        }
        attachWeatherWidget(pendingWeatherWidgetId, pendingWeatherProvider);
        pendingWeatherWidgetId = INVALID_WIDGET_ID;
        pendingWeatherProvider = null;
    }

    private void attachWeatherWidget(int id, AppWidgetProviderInfo info) {
        if (!isManufacturerWeatherProvider(info) || rootLayout == null) {
            sendWeatherStatus("O widget encontrado não pertence ao clima do fabricante.", false);
            return;
        }
        if (weatherWidgetView != null) rootLayout.removeView(weatherWidgetView);

        AppWidgetHostView hostView = weatherWidgetHost.createView(this, id, info);
        hostView.setAppWidget(id, info);
        hostView.setFocusable(true);
        hostView.setVisibility(View.GONE);
        weatherWidgetView = hostView;
        weatherWidgetId = id;
        rootLayout.addView(hostView, new FrameLayout.LayoutParams(1, 1,
                Gravity.TOP | Gravity.LEFT));
        weatherPreferences().edit().putInt(WEATHER_WIDGET_ID_KEY, id).apply();

        sendWeatherStatus(
                "Widget oficial do Daily Weather conectado. A previsão mostrada é fornecida pelo serviço de clima do telefone.",
                true);
        layoutWeatherWidget();
    }

    private void restoreWeatherWidgetIfAvailable() {
        if (weatherWidgetView != null || appWidgetManager == null || weatherWidgetHost == null) return;
        int savedId = weatherPreferences().getInt(WEATHER_WIDGET_ID_KEY, INVALID_WIDGET_ID);
        if (savedId == INVALID_WIDGET_ID) return;
        AppWidgetProviderInfo info = appWidgetManager.getAppWidgetInfo(savedId);
        if (isManufacturerWeatherProvider(info)) {
            weatherWidgetId = savedId;
            attachWeatherWidget(savedId, info);
        } else {
            weatherPreferences().edit().remove(WEATHER_WIDGET_ID_KEY).apply();
        }
    }

    private void layoutWeatherWidget() {
        if (weatherWidgetView == null || rootLayout == null || webView == null) return;
        if (!weatherTabVisible || weatherWidgetWidth <= 0 || weatherWidgetHeight <= 0) {
            weatherWidgetView.setVisibility(View.GONE);
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        int left = Math.max(0, Math.round(weatherWidgetLeft * density));
        int top = Math.max(0, Math.round(weatherWidgetTop * density));
        int width = Math.max(1, Math.round(weatherWidgetWidth * density));
        int height = Math.max(1, Math.round(weatherWidgetHeight * density));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                width, height, Gravity.TOP | Gravity.LEFT);
        params.leftMargin = left;
        params.topMargin = top;
        weatherWidgetView.setLayoutParams(params);
        weatherWidgetView.setVisibility(View.VISIBLE);
        weatherWidgetView.bringToFront();
    }

    private void clearPendingWeatherWidget() {
        if (pendingWeatherWidgetId != INVALID_WIDGET_ID && weatherWidgetHost != null) {
            weatherWidgetHost.deleteAppWidgetId(pendingWeatherWidgetId);
        }
        pendingWeatherWidgetId = INVALID_WIDGET_ID;
        pendingWeatherProvider = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_BIND_WEATHER_WIDGET) {
            if (resultCode == RESULT_OK && pendingWeatherProvider != null) {
                finishWeatherWidgetBinding(createWeatherWidgetOptions(pendingWeatherProvider));
            } else {
                clearPendingWeatherWidget();
                sendWeatherStatus("A autorização do widget oficial foi cancelada.", false);
            }
            return;
        }
        if (requestCode == REQUEST_CONFIGURE_WEATHER_WIDGET) {
            if (resultCode == RESULT_OK && pendingWeatherProvider != null) {
                attachWeatherWidget(pendingWeatherWidgetId, pendingWeatherProvider);
                pendingWeatherWidgetId = INVALID_WIDGET_ID;
                pendingWeatherProvider = null;
            } else {
                clearPendingWeatherWidget();
                sendWeatherStatus("A configuração do widget não foi concluída.", false);
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (weatherWidgetHost != null) {
            try { weatherWidgetHost.startListening(); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onStop() {
        if (weatherWidgetHost != null) {
            try { weatherWidgetHost.stopListening(); } catch (Exception ignored) {}
        }
        super.onStop();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || !compassRequested) return;

        float[] rotationMatrix = new float[9];
        float[] orientation = new float[3];

        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            SensorManager.getOrientation(rotationMatrix, orientation);
            publishHeading(orientation[0]);
            return;
        }

        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, accelerometerReading, 0, 3);
            hasAccelerometerReading = true;
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magneticFieldReading, 0, 3);
            hasMagneticFieldReading = true;
        }

        if (hasAccelerometerReading && hasMagneticFieldReading
                && SensorManager.getRotationMatrix(
                        rotationMatrix, null, accelerometerReading, magneticFieldReading)) {
            SensorManager.getOrientation(rotationMatrix, orientation);
            publishHeading(orientation[0]);
        }
    }

    private void publishHeading(float azimuthRadians) {
        double heading = (Math.toDegrees(azimuthRadians) + 360.0) % 360.0;
        final String value = String.format(java.util.Locale.US, "%.1f", heading);

        if (webView != null) {
            webView.post(() -> webView.evaluateJavascript(
                    "if (window.onChronosCompassHeading) window.onChronosCompassHeading(" +
                            value + ");", null));
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // O Android controla a calibração e a precisão do sensor magnético.
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enableImmersiveFullscreen();
    }

    @Override
    protected void onPause() {
        unregisterCompassSensors();
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        if (compassRequested) registerCompassSensors();
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
        compassRequested = false;
        unregisterCompassSensors();
        if (weatherWidgetHost != null) {
            try { weatherWidgetHost.stopListening(); } catch (Exception ignored) {}
        }
        if (weatherWidgetView != null && rootLayout != null) {
            rootLayout.removeView(weatherWidgetView);
            weatherWidgetView = null;
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
