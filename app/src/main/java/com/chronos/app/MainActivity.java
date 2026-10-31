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

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * Activity principal do Chronos.
 * A bússola usa os sensores físicos do aparelho, sem GPS, localização ou internet.
 */
public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private WebView webView;
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

        webView = new WebView(this);
        setContentView(webView);

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
        webView.setWebViewClient(new WebViewClient());
        webView.setBackgroundColor(0xFF0F172A);
        webView.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void enableImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
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
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
