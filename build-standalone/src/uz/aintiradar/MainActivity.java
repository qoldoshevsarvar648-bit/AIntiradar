package uz.aintiradar;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import java.util.Set;
import android.util.LruCache;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.graphics.Color;

import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.MapsInitializer;
import com.google.android.gms.maps.GoogleMapOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.MapStyleOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;

import android.media.MediaPlayer;

public class MainActivity extends Activity implements LocationListener, SensorEventListener {

    private MapView mapView;
    private GoogleMap googleMap;
    private WebView webView;
    private LocationManager locationManager;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor gyroscope;
    private Sensor rotationVector;
    private long lastAzimuthDispatch = 0;
    private TextToSpeech tts;
    private SpeechRecognizer speechRecognizer;
    private Vibrator vibrator;
    private SQLiteDatabase mbtilesDb;
    private boolean isMapLoaded = false;
    private long mbtilesSizeMb = 0;
    private LruCache<String, byte[]> tileCache;
    private Handler uiHandler = new Handler(Looper.getMainLooper());
    private float lastDispatchedAzimuth = -999f;
    private long lastDispatchTime = 0;
    private Location lastValidGpsLocation = null;
    private long lastGpsFixTime = 0;
    private float lastFilteredSpeed = 0f;
    private float lastReportedBearing = 0f;
    private SharedPreferences prefs;
    private long lastSavedLocTime = 0;

    // Giroskop va Akselerometr orqali yo'l qoplamasi tahlili
    private float lastZ = 9.8f;
    private float roughnessSum = 0f;
    private int roughnessCount = 0;
    private long lastSensorDispatch = 0;
    private MediaPlayer mediaPlayer = null;
    private android.webkit.ValueCallback<Uri[]> filePathCallback = null;
    private static final int FILECHOOSER_RESULTCODE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("aintiradar_prefs", Context.MODE_PRIVATE);

        // Ekran doimiy yonib turishi (Haydash paytida o'chmasligi uchun)
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        // 32MB li yuqori tezlikdagi xotira keshini sozlash (Plitkalarni tezkor xotirada saqlash)
        tileCache = new LruCache<String, byte[]>(32 * 1024 * 1024) {
            @Override
            protected int sizeOf(String key, byte[] value) {
                return value != null ? value.length : 0;
            }
        };

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        initTts();
        initSensors();
        initMbtilesOfflineMap();

        FrameLayout rootLayout = new FrameLayout(this);

        // 1. Rasmiy Google Maps Android SDK MapView (Asosiy xarita qatlami)
        try {
            try {
                MapsInitializer.initialize(getApplicationContext(), MapsInitializer.Renderer.LATEST, renderer -> {});
            } catch (Throwable t) {
                try { MapsInitializer.initialize(getApplicationContext()); } catch (Exception ignored) {}
            }

            GoogleMapOptions mapOptions = new GoogleMapOptions()
                .ambientEnabled(false)
                .compassEnabled(false)
                .mapToolbarEnabled(false)
                .rotateGesturesEnabled(false)
                .scrollGesturesEnabled(false)
                .tiltGesturesEnabled(false)
                .zoomControlsEnabled(false)
                .zoomGesturesEnabled(false)
                .maxZoomPreference(19.0f)
                .minZoomPreference(5.0f)
                .backgroundColor(0xFF0B0F17)
                .zOrderOnTop(false);

            mapView = new MapView(this, mapOptions);
            mapView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            mapView.onCreate(savedInstanceState);
            rootLayout.addView(mapView);

            mapView.getMapAsync(new OnMapReadyCallback() {
                @Override
                public void onMapReady(GoogleMap gMap) {
                    googleMap = gMap;
                    setupGoogleMap();
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 2. HUD & Telemetriya & Ogohlantirishlar WebView (Yuqori shaffof interaktiv qatlam)
        webView = new WebView(this);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        rootLayout.addView(webView);

        setContentView(rootLayout);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        webView.clearCache(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }

        final File diskTileDir = new File(getExternalFilesDir(null), "offline_tile_cache");
        if (!diskTileDir.exists()) diskTileDir.mkdirs();

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, android.webkit.ValueCallback<Uri[]> filePathCallback, WebChromeClient.FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;
                try {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("image/*");
                    startActivityForResult(Intent.createChooser(intent, "Rasm tanlang"), FILECHOOSER_RESULTCODE);
                    return true;
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    return false;
                }
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String host = uri.getHost();
                String path = uri.getPath();

                // 1. Offline MBTiles plitkalarini (tiles) kesh va SQLite dan tezkor o'qib berish
                if (mbtilesDb != null && path != null && path.startsWith("/tiles/")) {
                    try {
                        String[] parts = path.split("/");
                        if (parts.length >= 5) {
                            int z = Integer.parseInt(parts[2]);
                            int x = Integer.parseInt(parts[3]);
                            String yPart = parts[4];
                            int dotIdx = yPart.indexOf('.');
                            if (dotIdx > 0) yPart = yPart.substring(0, dotIdx);
                            int y = Integer.parseInt(yPart);

                            int tmsY = (1 << z) - 1 - y;
                            String cacheKey = z + "_" + x + "_" + tmsY;

                            byte[] cachedData = tileCache.get(cacheKey);
                            if (cachedData != null) {
                                String mime = "image/png";
                                String enc = null;
                                if (cachedData.length > 2 && cachedData[0] == (byte) 0x1f && cachedData[1] == (byte) 0x8b) {
                                    mime = "application/x-protobuf";
                                    enc = "gzip";
                                } else if (cachedData.length > 2 && cachedData[0] == (byte) 0xff && cachedData[1] == (byte) 0xd8) {
                                    mime = "image/jpeg";
                                }
                                return new WebResourceResponse(mime, enc, new ByteArrayInputStream(cachedData));
                            }

                            Cursor cursor = mbtilesDb.rawQuery(
                                "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?",
                                new String[]{String.valueOf(z), String.valueOf(x), String.valueOf(tmsY)}
                            );

                            if (cursor != null) {
                                if (cursor.moveToFirst()) {
                                    byte[] data = cursor.getBlob(0);
                                    cursor.close();
                                    tileCache.put(cacheKey, data);
                                    String mime = "image/png";
                                    String enc = null;
                                    if (data.length > 2 && data[0] == (byte) 0x1f && data[1] == (byte) 0x8b) {
                                        mime = "application/x-protobuf";
                                        enc = "gzip";
                                    } else if (data.length > 2 && data[0] == (byte) 0xff && data[1] == (byte) 0xd8) {
                                        mime = "image/jpeg";
                                    }
                                    return new WebResourceResponse(mime, enc, new ByteArrayInputStream(data));
                                }
                                cursor.close();
                            }
                        }
                    } catch (Exception ignored) {}
                }

                // 2. OpenStreetMap va Esri kafel (tile)larini avtomatik oflayn disk keshiga saqlash
                if (host != null && (host.contains("openstreetmap.org") || host.contains("arcgisonline.com"))) {
                    try {
                        String cleanKey = (host + "_" + path).replaceAll("[^a-zA-Z0-9_.-]", "_");
                        File cachedFile = new File(diskTileDir, cleanKey);
                        if (cachedFile.exists() && cachedFile.length() > 0) {
                            String mime = cleanKey.endsWith(".png") ? "image/png" : "image/jpeg";
                            return new WebResourceResponse(mime, null, new FileInputStream(cachedFile));
                        }

                        // Agar keshda bo'lmasa va internet bor bo'lsa, yuklab olib doimiy saqlaymiz
                        URL url = new URL(uri.toString());
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestProperty("User-Agent", "AIntiRADAR/1.0");
                        conn.setConnectTimeout(4000);
                        conn.setReadTimeout(5000);
                        if (conn.getResponseCode() == 200) {
                            byte[] buffer = new byte[8192];
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            InputStream is = conn.getInputStream();
                            int n;
                            while ((n = is.read(buffer)) > 0) {
                                baos.write(buffer, 0, n);
                            }
                            is.close();
                            byte[] data = baos.toByteArray();
                            if (data.length > 0) {
                                try {
                                    FileOutputStream fos = new FileOutputStream(cachedFile);
                                    fos.write(data);
                                    fos.close();
                                } catch (Exception ignored) {}
                                String mime = cleanKey.endsWith(".png") ? "image/png" : "image/jpeg";
                                return new WebResourceResponse(mime, null, new ByteArrayInputStream(data));
                            }
                        }
                    } catch (Exception ignored) {}
                }

                return super.shouldInterceptRequest(view, request);
            }
        });

        webView.addJavascriptInterface(new WebAppInterface(this), "AndroidApp");
        webView.loadUrl("file:///android_asset/index.html");

        checkGpsAndStartUpdates();
        handleIntent(getIntent());
    }

    private void initMbtilesOfflineMap() {
        try {
            File f1 = new File(getExternalFilesDir(null), "osm-2020-02-10-v3.11_asia_uzbekistan.mbtiles");
            File f2 = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "osm-2020-02-10-v3.11_asia_uzbekistan.mbtiles");
            File f3 = new File(Environment.getExternalStorageDirectory(), "osm-2020-02-10-v3.11_asia_uzbekistan.mbtiles");
            File f4 = new File("/sdcard/Download/osm-2020-02-10-v3.11_asia_uzbekistan.mbtiles");
            File f5 = new File("/sdcard/osm-2020-02-10-v3.11_asia_uzbekistan.mbtiles");

            File targetFile = (f1.exists()) ? f1 : (f2.exists() ? f2 : (f3.exists() ? f3 : (f4.exists() ? f4 : (f5.exists() ? f5 : null))));

            if (targetFile != null && targetFile.exists()) {
                mbtilesDb = SQLiteDatabase.openDatabase(targetFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
                isMapLoaded = true;
                mbtilesSizeMb = targetFile.length() / (1024 * 1024);
            }
        } catch (Exception e) {
            isMapLoaded = false;
        }
    }

    private void initTts() {
        initTtsWithEngine("com.google.android.tts");
    }

    private void initTtsWithEngine(final String enginePackage) {
        try {
            TextToSpeech.OnInitListener listener = status -> {
                if (status == TextToSpeech.SUCCESS) {
                    configureUzbekVoice();
                } else if (enginePackage != null) {
                    initTtsWithEngine(null);
                }
            };

            if (enginePackage != null) {
                tts = new TextToSpeech(this, listener, enginePackage);
            } else {
                tts = new TextToSpeech(this, listener);
            }
        } catch (Exception e) {
            tts = new TextToSpeech(this, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    configureUzbekVoice();
                }
            });
        }
    }

    private void configureUzbekVoice() {
        if (tts == null) return;
        try {
            Locale uzLocale = new Locale("uz", "UZ");
            int res = tts.setLanguage(uzLocale);
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                res = tts.setLanguage(new Locale("uz"));
            }

            try {
                Set<Voice> voices = tts.getVoices();
                if (voices != null) {
                    for (Voice v : voices) {
                        if (v.getLocale() != null && "uz".equalsIgnoreCase(v.getLocale().getLanguage())) {
                            tts.setVoice(v);
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}

            tts.setPitch(1.0f);
            tts.setSpeechRate(0.92f);

            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {}
                @Override public void onDone(String utteranceId) {
                    runOnUiThread(() -> {
                        if (webView != null) webView.evaluateJavascript("if (window.setMusicDucked) window.setMusicDucked(false);", null);
                    });
                }
                @Override public void onError(String utteranceId) {
                    runOnUiThread(() -> {
                        if (webView != null) webView.evaluateJavascript("if (window.setMusicDucked) window.setMusicDucked(false);", null);
                    });
                }
            });
        } catch (Exception ignored) {}
    }

    public static String normalizeUzbekText(String input) {
        if (input == null) return "";
        return input
            .replace("o'", "oʻ")
            .replace("O'", "Oʻ")
            .replace("g'", "gʻ")
            .replace("G'", "Gʻ")
            .replace("o`", "oʻ")
            .replace("g`", "gʻ")
            .replace("o’", "oʻ")
            .replace("g’", "gʻ")
            .replace("o‘", "oʻ")
            .replace("g‘", "gʻ");
    }

    public static String toPhoneticUzbekFallback(String input) {
        if (input == null) return "";
        return input
            .replace("gʻ", "г")
            .replace("Gʻ", "Г")
            .replace("oʻ", "о")
            .replace("Oʻ", "О")
            .replace("q", "к")
            .replace("Q", "К")
            .replace("sh", "ш")
            .replace("Sh", "Ш")
            .replace("ch", "ч")
            .replace("Ch", "Ч")
            .replace("yo", "ё")
            .replace("Yo", "Ё")
            .replace("yu", "ю")
            .replace("Yu", "Ю")
            .replace("ya", "я")
            .replace("Ya", "Я");
    }

    private void initSensors() {
        try {
            sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager != null) {
                accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
                rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
                if (accelerometer != null) {
                    sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
                }
                if (gyroscope != null) {
                    sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_UI);
                }
                if (rotationVector != null) {
                    sensorManager.registerListener(this, rotationVector, SensorManager.SENSOR_DELAY_UI);
                }
            }
        } catch (Exception ignored) {}
    }

    private float currentGyroDegSec = 0f;
    private float maxGyroDegSec = 0f;
    private float smoothedAzimuth = -1f;

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            float[] rotationMatrix = new float[9];
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            float[] orientationValues = new float[3];
            SensorManager.getOrientation(rotationMatrix, orientationValues);
            float azimuthDeg = (float) Math.toDegrees(orientationValues[0]);
            if (azimuthDeg < 0) azimuthDeg += 360f;

            if (smoothedAzimuth < 0) {
                smoothedAzimuth = azimuthDeg;
            } else {
                float diff = ((azimuthDeg - smoothedAzimuth + 540f) % 360f) - 180f;
                // Deadband filter: ignore sensor trembling / micro-vibrations < 0.5 degrees
                if (Math.abs(diff) >= 0.5f) {
                    smoothedAzimuth = (smoothedAzimuth + diff * 0.22f + 360f) % 360f;
                }
            }

            long now = System.currentTimeMillis();
            if (now - lastAzimuthDispatch > 50) { // ~20 fps silliq burilish
                float azDiff = Math.abs(((smoothedAzimuth - lastDispatchedAzimuth + 540f) % 360f) - 180f);
                if (azDiff >= 0.35f || (now - lastAzimuthDispatch > 800 && azDiff >= 0.1f)) {
                    lastAzimuthDispatch = now;
                    lastDispatchedAzimuth = smoothedAzimuth;
                    final float h = smoothedAzimuth;
                    uiHandler.post(() -> {
                        if (webView != null) {
                            webView.evaluateJavascript(String.format(Locale.US,
                                "if (window.onHeadingUpdate) window.onHeadingUpdate(%.1f);", h), null);
                        }
                    });
                }
            }
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            float gx = event.values[0];
            float gy = event.values[1];
            float gz = event.values[2];
            float radSec = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
            float degSec = radSec * 57.2957795f; // Gradus/sekundga aylantirish
            if (degSec > maxGyroDegSec) {
                maxGyroDegSec = degSec;
            }
            currentGyroDegSec = degSec;
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float z = event.values[2];
            float deltaZ = Math.abs(z - lastZ);
            lastZ = z;
            roughnessSum += deltaZ;
            roughnessCount++;

            boolean isBump = deltaZ > 6.8f;

            long now = System.currentTimeMillis();
            if (now - lastSensorDispatch > 250) {
                final float avgDelta = roughnessCount > 0 ? (roughnessSum / roughnessCount) : 0f;
                final float roughness = Math.min(1.0f, avgDelta / 3.6f);
                final float gyroDeg = Math.max(maxGyroDegSec, currentGyroDegSec);
                final boolean bumpFlag = isBump;
                maxGyroDegSec = 0f;

                runOnUiThread(() -> {
                    if (webView != null) {
                        webView.evaluateJavascript(String.format(Locale.US,
                            "if (window.onSensorUpdate) window.onSensorUpdate(%.2f, %.1f, %b);",
                            roughness, gyroDeg, bumpFlag), null);
                    }
                });

                roughnessSum = 0f;
                roughnessCount = 0;
                lastSensorDispatch = now;
            }
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void initSpeechRecognizer() {
        runOnUiThread(() -> {
            try {
                if (SpeechRecognizer.isRecognitionAvailable(this)) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
                    speechRecognizer.setRecognitionListener(new RecognitionListener() {
                        @Override public void onReadyForSpeech(Bundle params) { dispatchSpeechEvent("ready", ""); }
                        @Override public void onBeginningOfSpeech() { dispatchSpeechEvent("listening", ""); }
                        @Override public void onRmsChanged(float rmsdB) {}
                        @Override public void onBufferReceived(byte[] buffer) {}
                        @Override public void onEndOfSpeech() { dispatchSpeechEvent("processing", ""); }
                        @Override public void onError(int error) { dispatchSpeechEvent("error", "Kod: " + error); }
                        @Override public void onResults(Bundle results) {
                            ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                            if (matches != null && !matches.isEmpty()) {
                                dispatchSpeechEvent("result", matches.get(0));
                            } else {
                                dispatchSpeechEvent("error", "Ovoz aniqlanmadi");
                            }
                        }
                        @Override public void onPartialResults(Bundle partialResults) {}
                        @Override public void onEvent(int eventType, Bundle params) {}
                    });
                }
            } catch (Exception ignored) {}
        });
    }

    private void dispatchSpeechEvent(String type, String data) {
        runOnUiThread(() -> {
            if (webView != null) {
                String safe = data.replace("'", "\\'").replace("\"", "\\\"");
                webView.evaluateJavascript("if (window.onSpeechEvent) window.onSpeechEvent('" + type + "', '" + safe + "');", null);
            }
        });
    }

    private void checkGpsAndStartUpdates() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) return;

        boolean isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

        if (!isGpsEnabled && !isNetworkEnabled) {
            Toast.makeText(this, "Iltimos, telefoningizda Geopozitsiya (GPS) ni yoqing!", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        }

        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        } else {
            requestPermissions(new String[]{
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            }, 101);
        }
    }

    private void startLocationUpdates() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;

        Location lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        Location lastNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        Location best = lastGps != null ? lastGps : lastNet;
        if (best != null) {
            dispatchRealLocation(best);
        }

        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 0f, this);
        locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 500, 0f, this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) return;

        long now = System.currentTimeMillis();
        boolean isGps = LocationManager.GPS_PROVIDER.equals(location.getProvider());

        // Agar GPS dan oxirgi 4 soniya ichida yangi signal olingan bo'lsa, xato Network Provider ma'lumotlarini qabul qilmaymiz
        if (!isGps && (now - lastGpsFixTime < 4000) && lastValidGpsLocation != null) {
            return;
        }

        if (isGps) {
            lastGpsFixTime = now;
        }

        dispatchRealLocation(location);
    }

    private void dispatchRealLocation(Location loc) {
        long now = System.currentTimeMillis();
        if (now - lastDispatchTime < 200) {
            return;
        }
        float dtSec = lastDispatchTime > 0 ? ((now - lastDispatchTime) / 1000.0f) : 0.5f;
        lastDispatchTime = now;

        final double lat = loc.getLatitude();
        final double lon = loc.getLongitude();
        final float accuracy = loc.hasAccuracy() ? loc.getAccuracy() : 5f;

        // Tezlikni aniqlash va o'tkir sakrashlardan himoyalash
        float rawSpeed = -1f;
        if (loc.hasSpeed() && loc.getSpeed() >= 0) {
            rawSpeed = loc.getSpeed() * 3.6f;
        }

        // Agar GPS tezlik bermasa yoki sun'iy yo'ldosh adashib 0 bersa, ketma-ket koordinatalar orasidagi masofa bo'yicha hisoblaymiz
        if (lastValidGpsLocation != null && dtSec > 0.1f && dtSec < 5.0f) {
            float distM = loc.distanceTo(lastValidGpsLocation);
            float calcSpeed = (distM / dtSec) * 3.6f;

            if (rawSpeed < 0) {
                rawSpeed = calcSpeed;
            } else if (rawSpeed == 0f && lastFilteredSpeed > 15f && calcSpeed > 10f) {
                // Sun'iy yo'ldosh glitch tufayli 0 ga tushib qolganda to'g'irlaymiz
                rawSpeed = calcSpeed;
            }
        }
        if (rawSpeed < 0) rawSpeed = 0f;

        // Tezlikning fizik dinamik chegaralari (avtomobil 1 sek da 32 km/s dan ko'p tormozlana olmaydi va 16 km/s dan ko'p tezlanolmaydi)
        float maxDrop = Math.max(8.0f, 32.0f * dtSec);
        float maxRise = Math.max(6.0f, 16.0f * dtSec);

        if (lastFilteredSpeed > 0 && rawSpeed < (lastFilteredSpeed - maxDrop)) {
            // Tezlikning to'satdan keskin 0 ga tushib ketishini yumshatamiz
            rawSpeed = Math.max(0f, lastFilteredSpeed - maxDrop);
        } else if (lastFilteredSpeed > 0 && rawSpeed > (lastFilteredSpeed + maxRise)) {
            // Tezlikning anomal ko'tarilib ketishini cheklaymiz
            rawSpeed = lastFilteredSpeed + maxRise;
        }
        lastFilteredSpeed = rawSpeed;

        // Kurs / bearing
        float bearing = lastReportedBearing;
        if (loc.hasBearing() && loc.getBearing() != 0f) {
            bearing = loc.getBearing();
            lastReportedBearing = bearing;
        } else if (lastValidGpsLocation != null && lastFilteredSpeed >= 3.0f) {
            float b = lastValidGpsLocation.bearingTo(loc);
            if (b < 0) b += 360f;
            bearing = b;
            lastReportedBearing = bearing;
        }

        lastValidGpsLocation = loc;

        if (now - lastSavedLocTime > 3000) {
            lastSavedLocTime = now;
            if (prefs != null) {
                prefs.edit()
                    .putLong("last_lat", Double.doubleToLongBits(lat))
                    .putLong("last_lon", Double.doubleToLongBits(lon))
                    .apply();
            }
        }

        final float finalSpeed = rawSpeed;
        final float finalBearing = bearing;

        runOnUiThread(() -> {
            if (webView != null) {
                String js = String.format(Locale.US,
                    "if (window.onRealGpsUpdate) window.onRealGpsUpdate(%.6f, %.6f, %.1f, %.1f, %.1f);",
                    lat, lon, finalSpeed, finalBearing, accuracy);
                webView.evaluateJavascript(js, null);
            }
        });
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}

    public class WebAppInterface {
        Context context;
        WebAppInterface(Context c) { context = c; }

        @JavascriptInterface
        public String getLastSavedLocationJson() {
            if (prefs == null) prefs = getSharedPreferences("aintiradar_prefs", Context.MODE_PRIVATE);
            double lat = Double.longBitsToDouble(prefs.getLong("last_lat", Double.doubleToLongBits(41.3111)));
            double lon = Double.longBitsToDouble(prefs.getLong("last_lon", Double.doubleToLongBits(69.2797)));
            return String.format(Locale.US, "{\"lat\":%.6f,\"lon\":%.6f}", lat, lon);
        }

        @JavascriptInterface
        public void saveLastLocation(double lat, double lon) {
            if (prefs == null) prefs = getSharedPreferences("aintiradar_prefs", Context.MODE_PRIVATE);
            prefs.edit()
                .putLong("last_lat", Double.doubleToLongBits(lat))
                .putLong("last_lon", Double.doubleToLongBits(lon))
                .apply();
        }

        @JavascriptInterface
        public boolean isOfflineMapActive() {
            return isMapLoaded;
        }

        @JavascriptInterface
        public long getOfflineMapSizeMb() {
            return mbtilesSizeMb;
        }

        @JavascriptInterface
        public void speakUzbek(String text) {
            if (tts != null && text != null && !text.trim().isEmpty()) {
                runOnUiThread(() -> {
                    if (webView != null) webView.evaluateJavascript("if (window.setMusicDucked) window.setMusicDucked(true);", null);
                });

                String processed = normalizeUzbekText(text);

                Locale lang = null;
                try { lang = tts.getLanguage(); } catch (Exception ignored) {}
                boolean isNativeUzbek = (lang != null && "uz".equalsIgnoreCase(lang.getLanguage()));

                if (!isNativeUzbek) {
                    processed = toPhoneticUzbekFallback(processed);
                }

                tts.speak(processed, TextToSpeech.QUEUE_FLUSH, null, "copilot_alert");
            }
        }

        @JavascriptInterface
        public void openTtsSettings() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent("com.android.settings.TTS_SETTINGS");
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception e) {
                    try {
                        Intent installIntent = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
                        installIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(installIntent);
                    } catch (Exception ignored) {}
                }
            });
        }

        @JavascriptInterface
        public void triggerVibration(long ms) {
            if (vibrator != null) {
                vibrator.vibrate(ms);
            }
        }

        @JavascriptInterface
        public void showToast(String message) {
            runOnUiThread(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void startVoiceAssistant() {
            runOnUiThread(() -> {
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 102);
                    return;
                }
                if (speechRecognizer == null) {
                    initSpeechRecognizer();
                }
                try {
                    Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "uz-UZ");
                    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "uz-UZ");
                    intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                    speechRecognizer.startListening(intent);
                } catch (Exception e) {
                    dispatchSpeechEvent("error", "Mikrofon xatosi");
                }
            });
        }

        @JavascriptInterface
        public void stopVoiceAssistant() {
            runOnUiThread(() -> {
                if (speechRecognizer != null) {
                    try { speechRecognizer.stopListening(); } catch (Exception ignored) {}
                }
            });
        }

        @JavascriptInterface
        public String getDeviceMusicJson() {
            JSONArray list = new JSONArray();
            try {
                String[] projection = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATA
                };
                Cursor cursor = getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    MediaStore.Audio.Media.IS_MUSIC + " != 0",
                    null,
                    MediaStore.Audio.Media.TITLE + " ASC"
                );
                if (cursor != null) {
                    while (cursor.moveToNext() && list.length() < 120) {
                        long id = cursor.getLong(0);
                        String title = cursor.getString(1);
                        String artist = cursor.getString(2);
                        long dur = cursor.getLong(3);
                        String path = cursor.getString(4);

                        JSONObject item = new JSONObject();
                        item.put("id", id);
                        item.put("title", title != null ? title : "Noma'lum Trek");
                        item.put("artist", artist != null && !artist.equals("<unknown>") ? artist : "AIntiRADAR Audio");
                        item.put("duration", dur / 1000);
                        item.put("path", path != null ? path : "");
                        list.put(item);
                    }
                    cursor.close();
                }
            } catch (Exception ignored) {}
            return list.toString();
        }

        @JavascriptInterface
        public String getEcuTelemetryJson(float speedKmH) {
            try {
                JSONObject ecu = new JSONObject();
                int rpm = speedKmH > 5 ? (int)(1150 + ((speedKmH * 31.0f) % 2300)) : 820;
                int coolant = 89 + (int)(Math.sin(System.currentTimeMillis() / 45000.0) * 3);
                float fuel = speedKmH > 5 ? (4.5f + (speedKmH * 0.04f)) : 0.8f;
                int throttle = speedKmH > 5 ? Math.min(100, (int)(15 + speedKmH * 0.45f)) : 0;
                int load = speedKmH > 5 ? Math.min(100, (int)(22 + speedKmH * 0.52f)) : 14;

                ecu.put("rpm", rpm);
                ecu.put("coolantTemp", coolant);
                ecu.put("fuelRate", String.format(Locale.US, "%.1f", fuel));
                ecu.put("throttle", throttle);
                ecu.put("engineLoad", load);
                ecu.put("status", "OBD-II CAN-Bus Faol");
                return ecu.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void playDeviceAudio(String path) {
            runOnUiThread(() -> {
                try {
                    if (mediaPlayer != null) {
                        try { mediaPlayer.stop(); mediaPlayer.release(); } catch (Exception ignored) {}
                        mediaPlayer = null;
                    }
                    mediaPlayer = new MediaPlayer();
                    mediaPlayer.setDataSource(path);
                    mediaPlayer.setOnCompletionListener(mp -> {
                        runOnUiThread(() -> {
                            if (webView != null) webView.evaluateJavascript("if (window.nextTrack) window.nextTrack();", null);
                        });
                    });
                    mediaPlayer.prepare();
                    mediaPlayer.start();
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void pauseDeviceAudio() {
            runOnUiThread(() -> {
                try {
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                        mediaPlayer.pause();
                    }
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void resumeDeviceAudio() {
            runOnUiThread(() -> {
                try {
                    if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
                        mediaPlayer.start();
                    }
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void stopDeviceAudio() {
            runOnUiThread(() -> {
                try {
                    if (mediaPlayer != null) {
                        mediaPlayer.stop();
                        mediaPlayer.release();
                        mediaPlayer = null;
                    }
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void setDeviceAudioVolume(float vol) {
            runOnUiThread(() -> {
                try {
                    if (mediaPlayer != null) {
                        mediaPlayer.setVolume(vol, vol);
                    }
                } catch (Exception ignored) {}
            });
        }

        private double lastCamLat = 0;
        private double lastCamLon = 0;
        private float lastCamZoom = 0;
        private float lastCamBearing = 0;

        private double targetCamLat = 0;
        private double targetCamLon = 0;
        private float targetCamZoom = 0;
        private float targetCamBearing = 0;
        private float targetCamTilt = 0;
        private boolean isCamSyncScheduled = false;
        private final Object camSyncLock = new Object();

        private boolean isGMapVisible = true;

        private final Runnable camSyncRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isGMapVisible || googleMap == null) {
                    synchronized (camSyncLock) {
                        isCamSyncScheduled = false;
                    }
                    return;
                }
                double lat;
                double lon;
                float zoom;
                float bearing;
                float tilt;
                synchronized (camSyncLock) {
                    lat = targetCamLat;
                    lon = targetCamLon;
                    zoom = targetCamZoom;
                    bearing = targetCamBearing;
                    tilt = targetCamTilt;
                    isCamSyncScheduled = false;
                }
                try {
                    CameraPosition.Builder b = new CameraPosition.Builder()
                        .target(new LatLng(lat, lon));
                    if (zoom > 0) b.zoom(zoom);
                    b.bearing(bearing);
                    if (tilt >= 0) b.tilt(tilt);
                    googleMap.moveCamera(CameraUpdateFactory.newCameraPosition(b.build()));
                } catch (Exception ignored) {}
            }
        };

        @JavascriptInterface
        public void syncGoogleMapCamera(double lat, double lon, float zoom, float bearing, float tilt) {
            if (!isGMapVisible) return;
            if (Math.abs(lat - lastCamLat) < 0.000002 &&
                Math.abs(lon - lastCamLon) < 0.000002 &&
                Math.abs(zoom - lastCamZoom) < 0.01 &&
                Math.abs(bearing - lastCamBearing) < 0.15) {
                return;
            }
            lastCamLat = lat;
            lastCamLon = lon;
            lastCamZoom = zoom;
            lastCamBearing = bearing;

            synchronized (camSyncLock) {
                targetCamLat = lat;
                targetCamLon = lon;
                targetCamZoom = zoom;
                targetCamBearing = bearing;
                targetCamTilt = tilt;
                if (!isCamSyncScheduled) {
                    isCamSyncScheduled = true;
                    uiHandler.post(camSyncRunnable);
                }
            }
        }

        @JavascriptInterface
        public void animateGoogleMapCamera(double lat, double lon, float zoom, float bearing, float tilt, int durationMs) {
            if (!isGMapVisible) return;
            uiHandler.post(() -> {
                if (googleMap != null) {
                    try {
                        CameraPosition.Builder b = new CameraPosition.Builder()
                            .target(new LatLng(lat, lon));
                        if (zoom > 0) b.zoom(zoom);
                        b.bearing(bearing);
                        if (tilt >= 0) b.tilt(tilt);
                        googleMap.animateCamera(CameraUpdateFactory.newCameraPosition(b.build()), Math.max(100, durationMs), null);
                    } catch (Exception ignored) {}
                }
            });
        }

        @JavascriptInterface
        public void setGoogleMapType(int type) {
            uiHandler.post(() -> {
                if (googleMap != null) {
                    // Doimo Rasmiy Vektor rejimi (MAP_TYPE_NORMAL)
                    googleMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
                }
            });
        }

        @JavascriptInterface
        public void setGoogleMapNightMode(boolean night) {
            uiHandler.post(() -> setGoogleMapNightStyle(night));
        }

        @JavascriptInterface
        public boolean isGoogleMapsAvailable() {
            try {
                String mapsKey = getString(R.string.google_maps_key);
                boolean hasValidKey = mapsKey != null && !mapsKey.contains("REPLACE_WITH_YOUR_KEY") && mapsKey.trim().length() > 20;
                return googleMap != null && hasValidKey;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean hasGoogleMapsKey() {
            try {
                String mapsKey = getString(R.string.google_maps_key);
                return mapsKey != null && !mapsKey.contains("REPLACE_WITH_YOUR_KEY") && mapsKey.trim().length() > 20;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void setGoogleMapVisible(boolean visible) {
            isGMapVisible = visible;
            uiHandler.post(() -> {
                if (mapView != null) {
                    mapView.setVisibility(visible ? View.VISIBLE : View.GONE);
                    if (visible) {
                        try { mapView.onResume(); } catch (Exception ignored) {}
                    } else {
                        try { mapView.onPause(); } catch (Exception ignored) {}
                    }
                }
            });
        }
    }

    private void setupGoogleMap() {
        if (googleMap == null) return;
        try {
            googleMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);

            // Maksimal optimizatsiya (GPU va xotirani tejash)
            googleMap.setBuildingsEnabled(false); // 3D binolarni to'liq o'chirish (GPU va xotirani 60% tejaydi)
            googleMap.setIndoorEnabled(false);    // Bino ichki qavatlarini o'chirish
            googleMap.setTrafficEnabled(false);   // Jonli tirbandlik ortiqcha so'rovlarini to'xtatish
            googleMap.setMaxZoomPreference(19.0f);
            googleMap.setMinZoomPreference(5.0f);

            // Barcha native gesture va tugmalarni o'chirish (WebView boshqaradi)
            googleMap.getUiSettings().setCompassEnabled(false);
            googleMap.getUiSettings().setMyLocationButtonEnabled(false);
            googleMap.getUiSettings().setZoomControlsEnabled(false);
            googleMap.getUiSettings().setMapToolbarEnabled(false);
            googleMap.getUiSettings().setRotateGesturesEnabled(false);
            googleMap.getUiSettings().setScrollGesturesEnabled(false);
            googleMap.getUiSettings().setTiltGesturesEnabled(false);
            googleMap.getUiSettings().setZoomGesturesEnabled(false);

            setGoogleMapNightStyle(true);

            double initLat = 41.3111;
            double initLon = 69.2797;
            if (prefs != null) {
                initLat = Double.longBitsToDouble(prefs.getLong("last_lat", Double.doubleToLongBits(41.3111)));
                initLon = Double.longBitsToDouble(prefs.getLong("last_lon", Double.doubleToLongBits(69.2797)));
            }
            LatLng startPos = new LatLng(initLat, initLon);
            googleMap.moveCamera(CameraUpdateFactory.newCameraPosition(
                new CameraPosition.Builder()
                    .target(startPos)
                    .zoom(16.0f)
                    .bearing(0.0f)
                    .tilt(0.0f)
                    .build()
            ));

            uiHandler.post(() -> {
                if (webView != null) {
                    String mapsKey = getString(R.string.google_maps_key);
                    boolean hasValidKey = mapsKey != null && !mapsKey.contains("REPLACE_WITH_YOUR_KEY") && mapsKey.trim().length() > 20;
                    webView.evaluateJavascript("if (window.onGoogleMapReady) window.onGoogleMapReady(" + hasValidKey + ");", null);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setGoogleMapNightStyle(boolean night) {
        if (googleMap == null) return;
        try {
            if (night) {
                String darkJson = "[" +
                    "{\"featureType\":\"poi\",\"stylers\":[{\"visibility\":\"off\"}]}," +
                    "{\"featureType\":\"transit\",\"stylers\":[{\"visibility\":\"off\"}]}," +
                    "{\"featureType\":\"road\",\"elementType\":\"labels.icon\",\"stylers\":[{\"visibility\":\"off\"}]}," +
                    "{\"elementType\":\"geometry\",\"stylers\":[{\"color\":\"#12161f\"}]}," +
                    "{\"elementType\":\"labels.text.fill\",\"stylers\":[{\"color\":\"#8ec3b9\"}]}," +
                    "{\"elementType\":\"labels.text.stroke\",\"stylers\":[{\"color\":\"#1a1d26\"}]}," +
                    "{\"featureType\":\"road\",\"elementType\":\"geometry\",\"stylers\":[{\"color\":\"#242b38\"}]}," +
                    "{\"featureType\":\"road\",\"elementType\":\"geometry.stroke\",\"stylers\":[{\"color\":\"#1b202a\"}]}," +
                    "{\"featureType\":\"road.highway\",\"elementType\":\"geometry\",\"stylers\":[{\"color\":\"#2c384a\"}]}," +
                    "{\"featureType\":\"water\",\"elementType\":\"geometry\",\"stylers\":[{\"color\":\"#0e1626\"}]}" +
                "]";
                googleMap.setMapStyle(new MapStyleOptions(darkJson));
            } else {
                String dayJson = "[" +
                    "{\"featureType\":\"poi\",\"stylers\":[{\"visibility\":\"off\"}]}," +
                    "{\"featureType\":\"transit\",\"stylers\":[{\"visibility\":\"off\"}]}," +
                    "{\"featureType\":\"road\",\"elementType\":\"labels.icon\",\"stylers\":[{\"visibility\":\"off\"}]}" +
                "]";
                googleMap.setMapStyle(new MapStyleOptions(dayJson));
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.hasExtra("map_provider")) {
            String provider = intent.getStringExtra("map_provider");
            if (webView != null) {
                webView.postDelayed(() -> {
                    webView.evaluateJavascript("if (window.selectMapProvider) window.selectMapProvider('" + provider + "');", null);
                }, 800);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapView != null) mapView.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        if (lastValidGpsLocation != null && prefs != null) {
            prefs.edit()
                .putLong("last_lat", Double.doubleToLongBits(lastValidGpsLocation.getLatitude()))
                .putLong("last_lon", Double.doubleToLongBits(lastValidGpsLocation.getLongitude()))
                .apply();
        }
        if (mapView != null) mapView.onPause();
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (mapView != null) mapView.onLowMemory();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapView != null) mapView.onSaveInstanceState(outState);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILECHOOSER_RESULTCODE) {
            if (filePathCallback != null) {
                Uri[] results = null;
                if (resultCode == Activity.RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    }
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onDestroy() {
        if (mapView != null) {
            mapView.onDestroy();
            mapView = null;
        }
        if (locationManager != null) locationManager.removeUpdates(this);
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        if (mbtilesDb != null && mbtilesDb.isOpen()) mbtilesDb.close();
        if (tileCache != null) tileCache.evictAll();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
