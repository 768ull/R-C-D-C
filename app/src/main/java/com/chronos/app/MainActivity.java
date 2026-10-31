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
 * Activity principal do Chronos App.
 * A interface HTML/CSS/JS permanece embutida no APK e funciona offline.
 * A bússola usa os sensores de orientação do próprio aparelho, sem localização
 * e sem conexão com a internet.
 */
public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private WebView webView;
    private SensorManager sensorManager;
    private Sensor rotationVectorSensor;
    private Sensor accelerometerSensor;
    private Sensor magneticSensor;
    private boolean compassRequested = false;
    private final float[] accelerometerReading = new float[3];
    private final float[] magneticReading = new float[3];
    private boolean hasAccelerometerReading = false;
    private boolean hasMagneticReading = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        enableImmersiveFullscreen();

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

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
                || (accelerometerSensor != null && magneticSensor != null);
    }

    private void registerCompassSensors() {
        if (sensorManager == null || !compassRequested) return;
        if (rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI);
        } else if (accelerometerSensor != null && magneticSensor != null) {
            sensorManager.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_UI);
            sensorManager.registerListener(this, magneticSensor, SensorManager.SENSOR_DELAY_UI);
        }
    }

    private void unregisterCompassSensors() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    private final class CompassBridge {
        @JavascriptInterface
        public boolean startCompass() {
            if (!hasCompassSensor()) return false;
            compassRequested = true;
            runOnUiThread(() -> registerCompassSensors());
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
        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            float[] rotationMatrix = new float[9];
            float[] orientation = new float[3];
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            SensorManager.getOrientation(rotationMatrix, orientation);
            publishHeading(orientation[0]);
            return;
        }

        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, accelerometerReading, 0, 3);
            hasAccelerometerReading = true;
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magneticReading, 0, 3);
            hasMagneticReading = true;
        }

        if (hasAccelerometerReading && hasMagneticReading) {
            float[] rotationMatrix = new float[9];
            float[] orientation = new float[3];
            if (SensorManager.getRotationMatrix(
                    rotationMatrix, null, accelerometerReading, magneticReading)) {
                SensorManager.getOrientation(rotationMatrix, orientation);
                publishHeading(orientation[0]);
            }
        }
    }

    private void publishHeading(float azimuthRadians) {
        double heading = (Math.toDegrees(azimuthRadians) + 360.0) % 360.0;
        final String value = String.format(java.util.Locale.US, "%.1f", heading);
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript(
                        "if (window.onChronosCompassHeading) window.onChronosCompassHeading("
                                + value + ");",
                        null);
            }
        });
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // O sensor informa a precisão ao sistema; a interface mantém a leitura contínua.
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
