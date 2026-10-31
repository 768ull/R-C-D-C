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
import android.net.Uri;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.provider.Settings;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.Manifest;
import android.content.SharedPreferences;
import android.os.Process;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
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
    private static final int REQUEST_WEATHER_LOCATION_PERMISSION = 8122;
    private static final int REQUEST_ALARM_TONE = 8124;
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
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mapSearchExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService weatherExecutor = Executors.newSingleThreadExecutor();
    private volatile long lastMapSearchTimeMs = 0L;
    private LocationManager weatherLocationManager;
    private LocationListener weatherLocationListener;
    private Location weatherFallbackLocation;
    private final Runnable weatherLocationTimeout = () -> finishWeatherLocationRequest(weatherFallbackLocation);

    // O WebView pode precisar de um novo ciclo de layout/desenho após voltar
    // do seletor de apps. Reaplica o layout depois que a janela recupera o foco.
    private final Runnable webViewRefreshRunnable = () -> {
        WebView current = webView;
        if (current == null || !current.isAttachedToWindow()) return;
        current.requestLayout();
        current.invalidate();
        current.postInvalidateOnAnimation();
        current.evaluateJavascript(
                "(function(){if(document.readyState==='complete'){"
                        + "window.dispatchEvent(new Event('resize'));}})();", null);
    };

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
        rootLayout.setBackgroundColor(getColor(R.color.window_background));
        rootLayout.setClipChildren(false);
        rootLayout.setClipToPadding(false);
        // Widgets de terceiros devem ser inflados com ApplicationContext.
        // Usar AppCompatActivity aqui pode fazer RemoteViews falhar e mostrar
        // a tela de erro "Não foi possível adicionar widget".
        Context appContext = getApplicationContext();
        appWidgetManager = AppWidgetManager.getInstance(appContext);
        weatherWidgetHost = new AppWidgetHost(appContext, WEATHER_HOST_ID);

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
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setTextZoom(100);
        settings.setUserAgentString(settings.getUserAgentString()
                + " Chronos/1.24 (+https://github.com/768ull/R-C-D-C)");

        // A interface JavaScript só é adicionada depois de ativar JavaScript.
        webView.addJavascriptInterface(new CompassBridge(), "ChronosCompass");
        webView.addJavascriptInterface(new AlarmBridge(), "ChronosAlarm");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
            }
        });
        // Usa a mesma cor da janela e do contêiner nativo para que a superfície
        // não revele uma cor diferente enquanto o WebView recompõe a página.
        webView.setBackgroundColor(getColor(R.color.window_background));
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

    private void scheduleWebViewRefreshAfterResume() {
        if (webView == null) return;
        mainHandler.removeCallbacks(webViewRefreshRunnable);
        // Dois ciclos cobrem tanto a retomada da superfície quanto o layout
        // final depois que o Android termina a transição entre aplicativos.
        mainHandler.postDelayed(webViewRefreshRunnable, 120L);
        mainHandler.postDelayed(webViewRefreshRunnable, 420L);
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

    private void sendMapSearchResult(JSONArray results, String error) {
        if (webView == null) return;
        String resultJson = results == null ? "[]" : results.toString();
        String errorJson = error == null ? "null" : JSONObject.quote(error);
        webView.post(() -> webView.evaluateJavascript(
                "if (window.onChronosMapSearchResult) window.onChronosMapSearchResult("
                        + resultJson + "," + errorJson + ");", null));
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
        public void searchMapPlace(String query) {
            if (query == null || query.trim().isEmpty()) {
                sendMapSearchResult(new JSONArray(), "Digite um endereço para pesquisar.");
                return;
            }
            final String safeQuery = query.trim();
            mapSearchExecutor.execute(() -> {
                HttpURLConnection connection = null;
                try {
                    long waitMs = 1100L - (System.currentTimeMillis() - lastMapSearchTimeMs);
                    if (waitMs > 0) Thread.sleep(waitMs);
                    lastMapSearchTimeMs = System.currentTimeMillis();

                    String encoded = URLEncoder.encode(safeQuery, StandardCharsets.UTF_8.name());
                    URL url = new URL("https://nominatim.openstreetmap.org/search?format=jsonv2"
                            + "&limit=5&addressdetails=1&accept-language=pt-BR&q=" + encoded);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(12000);
                    connection.setReadTimeout(12000);
                    connection.setRequestProperty("Accept", "application/json");
                    connection.setRequestProperty("User-Agent",
                            "Chronos/1.24 (+https://github.com/768ull/R-C-D-C)");

                    int responseCode = connection.getResponseCode();
                    InputStream stream = responseCode >= 200 && responseCode < 300
                            ? connection.getInputStream() : connection.getErrorStream();
                    if (stream == null) throw new IllegalStateException("Resposta vazia do serviço de pesquisa.");

                    StringBuilder body = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) body.append(line);
                    }
                    if (responseCode < 200 || responseCode >= 300) {
                        throw new IllegalStateException("O serviço de pesquisa respondeu HTTP " + responseCode + ".");
                    }

                    JSONArray source = new JSONArray(body.toString());
                    JSONArray results = new JSONArray();
                    for (int i = 0; i < source.length() && i < 5; i++) {
                        JSONObject item = source.getJSONObject(i);
                        JSONObject result = new JSONObject();
                        result.put("lat", item.optString("lat"));
                        result.put("lon", item.optString("lon"));
                        result.put("name", item.optString("display_name", "Local encontrado"));
                        results.put(result);
                    }
                    sendMapSearchResult(results, null);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    sendMapSearchResult(new JSONArray(), "Pesquisa cancelada.");
                } catch (Exception e) {
                    sendMapSearchResult(new JSONArray(),
                            "Não foi possível pesquisar agora. Verifique a internet e tente novamente.");
                } finally {
                    if (connection != null) connection.disconnect();
                }
            });
        }

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
        public void requestWeatherLocation() {
            runOnUiThread(() -> beginWeatherLocationRequest());
        }

        @JavascriptInterface
        public void searchWeatherCity(String query) {
            final String safeQuery = query == null ? "" : query.trim();
            if (safeQuery.isEmpty()) {
                sendWeatherCityResults(new JSONArray(), "Digite o nome de uma cidade.");
                return;
            }
            weatherExecutor.execute(() -> {
                try {
                    String encoded = URLEncoder.encode(safeQuery, StandardCharsets.UTF_8.name());
                    JSONObject response = requestWeatherJson(
                            "https://geocoding-api.open-meteo.com/v1/search?count=6"
                                    + "&language=pt&format=json&name=" + encoded);
                    JSONArray source = response.optJSONArray("results");
                    JSONArray cities = new JSONArray();
                    if (source != null) {
                        for (int i = 0; i < source.length() && i < 6; i++) {
                            JSONObject item = source.optJSONObject(i);
                            if (item == null) continue;
                            JSONObject city = new JSONObject();
                            city.put("name", item.optString("name", "Cidade"));
                            city.put("admin1", item.optString("admin1", ""));
                            city.put("country", item.optString("country", ""));
                            city.put("country_code", item.optString("country_code", ""));
                            city.put("latitude", item.optDouble("latitude", Double.NaN));
                            city.put("longitude", item.optDouble("longitude", Double.NaN));
                            cities.put(city);
                        }
                    }
                    sendWeatherCityResults(cities,
                            cities.length() == 0 ? "Nenhuma cidade encontrada. Tente informar também o estado ou país." : null);
                } catch (Exception e) {
                    sendWeatherCityResults(new JSONArray(),
                            "Não foi possível pesquisar a cidade. Verifique sua conexão e tente novamente.");
                }
            });
        }

        @JavascriptInterface
        public void fetchWeather(double latitude, double longitude, String cityName,
                                 String admin1, String country) {
            if (Double.isNaN(latitude) || Double.isInfinite(latitude)
                    || Double.isNaN(longitude) || Double.isInfinite(longitude)
                    || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
                sendWeatherForecast(null, "A localização escolhida é inválida.");
                return;
            }
            final String safeName = cityName == null ? "Local selecionado" : cityName;
            final String safeAdmin1 = admin1 == null ? "" : admin1;
            final String safeCountry = country == null ? "" : country;
            weatherExecutor.execute(() -> {
                try {
                    String coordinates = String.format(Locale.US,
                            "latitude=%.5f&longitude=%.5f", latitude, longitude);
                    String address = "https://api.open-meteo.com/v1/forecast?"
                            + coordinates
                            + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code,wind_speed_10m"
                            + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                            + "&forecast_days=5&timezone=auto";
                    JSONObject forecast = requestWeatherJson(address);
                    JSONObject location = new JSONObject();
                    location.put("name", safeName);
                    location.put("admin1", safeAdmin1);
                    location.put("country", safeCountry);
                    forecast.put("chronos_location", location);
                    sendWeatherForecast(forecast, null);
                } catch (Exception e) {
                    sendWeatherForecast(null,
                            "Não foi possível carregar a previsão agora. Confira a internet e tente novamente.");
                }
            });
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



    private final class AlarmBridge {
        @JavascriptInterface public String getAlarms(){return AlarmReceiver.alarmsJson(getApplicationContext());}
        @JavascriptInterface public void saveAlarm(String json){try{JSONObject a=new JSONObject(json);runOnUiThread(()->{
            if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                ActivityCompat.requestPermissions(MainActivity.this,new String[]{Manifest.permission.POST_NOTIFICATIONS},8123);
            AlarmReceiver.save(getApplicationContext(),a);
        });}catch(Exception ignored){}}
        @JavascriptInterface public void setAlarmEnabled(String id,boolean enabled){try{AlarmReceiver.enable(getApplicationContext(),Long.parseLong(id),enabled);}catch(Exception ignored){}}
        @JavascriptInterface public void deleteAlarm(String id){try{AlarmReceiver.delete(getApplicationContext(),Long.parseLong(id));}catch(Exception ignored){}}
        @JavascriptInterface public void pickAlarmTone(String existing){runOnUiThread(()->{try{
            Intent i=new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);i.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,RingtoneManager.TYPE_ALARM);
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE,"Som do alarme");i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT,true);
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT,false);if(existing!=null&&!existing.isEmpty())i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,Uri.parse(existing));
            startActivityForResult(i,REQUEST_ALARM_TONE);
        }catch(Exception e){publishAlarmTone(null,null);}});}
        @JavascriptInterface public void requestExactAlarmPermission(){runOnUiThread(()->{if(android.os.Build.VERSION.SDK_INT>=31){
            android.app.AlarmManager am=(android.app.AlarmManager)getSystemService(ALARM_SERVICE);
            if(am!=null&&!am.canScheduleExactAlarms())try{startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName())));}
            catch(Exception e){try{startActivity(new Intent(Settings.ACTION_SETTINGS));}catch(Exception ignored){}}
            else android.widget.Toast.makeText(MainActivity.this,"Alarmes exatos já estão permitidos.",android.widget.Toast.LENGTH_SHORT).show();
        }else android.widget.Toast.makeText(MainActivity.this,"Este Android não exige permissão especial.",android.widget.Toast.LENGTH_SHORT).show();});}
        @JavascriptInterface public void openSystemClock(){runOnUiThread(()->{try{
            Intent i=new Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS);if(i.resolveActivity(getPackageManager())!=null)startActivity(i);
            else startActivity(new Intent(android.provider.AlarmClock.ACTION_SET_ALARM).putExtra(android.provider.AlarmClock.EXTRA_MESSAGE,"Chronos"));
        }catch(Exception e){android.widget.Toast.makeText(MainActivity.this,"Relógio do telefone indisponível.",android.widget.Toast.LENGTH_SHORT).show();}});}
    }
    private void publishAlarmTone(Uri uri,String title){if(webView==null)return;String u=uri==null?"null":JSONObject.quote(uri.toString()),t=title==null?"null":JSONObject.quote(title);
        webView.post(()->webView.evaluateJavascript("if(window.onChronosAlarmTonePicked)window.onChronosAlarmTonePicked("+u+","+t+");",null));}

    private boolean hasWeatherLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void beginWeatherLocationRequest() {
        if (!hasWeatherLocationPermission()) {
            ActivityCompat.requestPermissions(this, new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQUEST_WEATHER_LOCATION_PERMISSION);
            sendWeatherStatus("Autorize a localização para buscar o clima da sua região.", false);
            return;
        }

        weatherLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (weatherLocationManager == null) {
            sendWeatherStatus("O serviço de localização não está disponível neste aparelho.", false);
            return;
        }

        weatherFallbackLocation = null;
        try {
            long now = System.currentTimeMillis();
            for (String provider : weatherLocationManager.getProviders(true)) {
                try {
                    Location known = weatherLocationManager.getLastKnownLocation(provider);
                    if (known == null) continue;
                    if (weatherFallbackLocation == null
                            || known.getTime() > weatherFallbackLocation.getTime()) {
                        weatherFallbackLocation = known;
                    }
                } catch (SecurityException ignored) {
                    // Um provedor pode não estar coberto pela permissão concedida.
                }
            }
            if (weatherFallbackLocation != null
                    && now - weatherFallbackLocation.getTime() <= 120000L) {
                sendWeatherLocation(weatherFallbackLocation);
                return;
            }

            weatherLocationListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    finishWeatherLocationRequest(location);
                }
                @Override public void onProviderEnabled(String provider) {}
                @Override public void onProviderDisabled(String provider) {}
                @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            };

            int requestedProviders = 0;
            for (String provider : weatherLocationManager.getProviders(true)) {
                try {
                    weatherLocationManager.requestSingleUpdate(
                            provider, weatherLocationListener, Looper.getMainLooper());
                    requestedProviders++;
                } catch (SecurityException | IllegalArgumentException ignored) {
                    // Tenta outro provedor disponível.
                }
            }
            if (requestedProviders == 0) {
                finishWeatherLocationRequest(weatherFallbackLocation);
                return;
            }
            mainHandler.removeCallbacks(weatherLocationTimeout);
            mainHandler.postDelayed(weatherLocationTimeout, 15000L);
            sendWeatherStatus("Obtendo sua localização…", false);
        } catch (SecurityException e) {
            sendWeatherStatus("A permissão de localização não foi concedida.", false);
        } catch (Exception e) {
            finishWeatherLocationRequest(weatherFallbackLocation);
        }
    }

    private void finishWeatherLocationRequest(Location location) {
        mainHandler.removeCallbacks(weatherLocationTimeout);
        if (weatherLocationManager != null && weatherLocationListener != null) {
            try { weatherLocationManager.removeUpdates(weatherLocationListener); }
            catch (SecurityException ignored) {}
        }
        weatherLocationListener = null;
        if (location == null && weatherFallbackLocation != null
                && System.currentTimeMillis() - weatherFallbackLocation.getTime() <= 21600000L) {
            location = weatherFallbackLocation;
        }
        weatherFallbackLocation = null;
        if (location == null) {
            sendWeatherStatus(
                    "Não foi possível obter sua localização. Ative o GPS ou pesquise uma cidade.",
                    false);
            return;
        }
        sendWeatherLocation(location);
    }

    private void sendWeatherLocation(Location location) {
        if (location == null || webView == null || isFinishing() || isDestroyed()) return;
        String latitude = String.format(Locale.US, "%.6f", location.getLatitude());
        String longitude = String.format(Locale.US, "%.6f", location.getLongitude());
        webView.post(() -> {
            WebView current = webView;
            if (current == null) return;
            current.evaluateJavascript(
                    "if(window.onChronosWeatherLocation)window.onChronosWeatherLocation("
                            + latitude + "," + longitude + ");", null);
        });
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_WEATHER_LOCATION_PERMISSION) return;
        if (hasWeatherLocationPermission()) {
            beginWeatherLocationRequest();
        } else {
            sendWeatherStatus(
                    "Permissão negada. Você ainda pode pesquisar uma cidade manualmente.",
                    false);
        }
    }

    private JSONObject requestWeatherJson(String address) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent",
                    "Chronos/1.25 (+https://github.com/768ull/R-C-D-C)");

            int responseCode = connection.getResponseCode();
            InputStream stream = responseCode >= 200 && responseCode < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) {
                throw new IllegalStateException("Resposta vazia do serviço meteorológico.");
            }

            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            if (responseCode < 200 || responseCode >= 300) {
                throw new IllegalStateException("O serviço meteorológico respondeu HTTP " + responseCode + ".");
            }
            return new JSONObject(body.toString());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void sendWeatherCityResults(JSONArray cities, String error) {
        if (webView == null) return;
        String data = cities == null ? "[]" : cities.toString();
        String errorJson = error == null ? "null" : JSONObject.quote(error);
        webView.post(() -> webView.evaluateJavascript(
                "if (window.onChronosWeatherCities) window.onChronosWeatherCities("
                        + data + "," + errorJson + ");", null));
    }

    private void sendWeatherForecast(JSONObject forecast, String error) {
        if (webView == null) return;
        String data = forecast == null ? "null" : forecast.toString();
        String errorJson = error == null ? "null" : JSONObject.quote(error);
        webView.post(() -> webView.evaluateJavascript(
                "if (window.onChronosWeatherForecast) window.onChronosWeatherForecast("
                        + data + "," + errorJson + ");", null));
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

        try {
            // createView já associa o ID e o AppWidgetProviderInfo à HostView.
            // Não chamar setAppWidget uma segunda vez.
            AppWidgetHostView hostView = weatherWidgetHost.createView(
                    getApplicationContext(), id, info);
            hostView.setFocusable(true);
            hostView.setVisibility(View.GONE);
            weatherWidgetView = hostView;
            weatherWidgetId = id;
            rootLayout.addView(hostView, new FrameLayout.LayoutParams(1, 1,
                    Gravity.TOP | Gravity.LEFT));
            weatherPreferences().edit().putInt(WEATHER_WIDGET_ID_KEY, id).apply();

            sendWeatherStatus("Permissão concedida. Verificando a visualização oficial do clima…", false);
            layoutWeatherWidget();
            verifyWeatherWidgetRendering(hostView, id, 0);
        } catch (Exception e) {
            if (weatherWidgetView != null) {
                rootLayout.removeView(weatherWidgetView);
                weatherWidgetView = null;
            }
            weatherWidgetId = INVALID_WIDGET_ID;
            weatherWidgetHost.deleteAppWidgetId(id);
            weatherPreferences().edit().remove(WEATHER_WIDGET_ID_KEY).apply();
            sendWeatherStatus(
                    "O Android autorizou a integração, mas não conseguiu renderizar o widget do Daily Weather.",
                    false);
        }
    }

    private void verifyWeatherWidgetRendering(
            AppWidgetHostView view, int appWidgetId, int attempt) {
        if (view == null || weatherWidgetView != view || weatherWidgetId != appWidgetId) return;

        if (containsWidgetRenderingError(view)) {
            view.setVisibility(View.GONE);
            rootLayout.removeView(view);
            weatherWidgetView = null;
            weatherWidgetId = INVALID_WIDGET_ID;
            weatherPreferences().edit().remove(WEATHER_WIDGET_ID_KEY).apply();
            weatherWidgetHost.deleteAppWidgetId(appWidgetId);
            sendWeatherStatus(
                    "O Android vinculou o widget, mas o Daily Weather não conseguiu fornecer uma visualização compatível para o Chronos. O serviço não será marcado como conectado.",
                    false);
            return;
        }

        // Dá alguns ciclos ao provedor para entregar RemoteViews ao host.
        if (attempt < 8) {
            mainHandler.postDelayed(
                    () -> verifyWeatherWidgetRendering(view, appWidgetId, attempt + 1),
                    750L);
        } else {
            sendWeatherStatus(
                    "Widget oficial do Daily Weather conectado. A previsão é fornecida pelo serviço de clima do telefone.",
                    true);
        }
    }

    private boolean containsWidgetRenderingError(View view) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null) {
                String value = text.toString().toLowerCase(Locale.ROOT);
                if (value.contains("não foi possível adicionar widget")
                        || value.contains("nao foi possivel adicionar widget")
                        || value.contains("couldn't add widget")
                        || value.contains("could not add widget")) {
                    return true;
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsWidgetRenderingError(group.getChildAt(i))) return true;
            }
        }
        return false;
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
        if(requestCode==REQUEST_ALARM_TONE){if(resultCode==RESULT_OK&&data!=null){Uri uri=data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);String title=null;if(uri!=null)try{Ringtone r=RingtoneManager.getRingtone(this,uri);if(r!=null)title=r.getTitle(this);}catch(Exception ignored){}publishAlarmTone(uri,title);}return;}
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
        if (hasFocus) {
            enableImmersiveFullscreen();
            scheduleWebViewRefreshAfterResume();
        }
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
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
            scheduleWebViewRefreshAfterResume();
        }
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
        mainHandler.removeCallbacks(weatherLocationTimeout);
        if (weatherLocationManager != null && weatherLocationListener != null) {
            try { weatherLocationManager.removeUpdates(weatherLocationListener); }
            catch (SecurityException ignored) {}
            weatherLocationListener = null;
        }
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
        mapSearchExecutor.shutdownNow();
        weatherExecutor.shutdownNow();
        super.onDestroy();
    }
}
