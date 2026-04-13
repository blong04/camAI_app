package com.example.camai_app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.example.camai_app.auth.LoginActivity;
import com.example.camai_app.auth.SessionManager;
import com.example.camai_app.detector.DetectionResult;
import com.example.camai_app.detector.OverlayView;
import com.example.camai_app.detector.Yolo11Detector;
import com.example.camai_app.history.AlertDbHelper;
import com.example.camai_app.history.HistoryActivity;
import com.example.camai_app.network.ApiClient;
import com.example.camai_app.network.AuthService;
import com.example.camai_app.network.MjpegStreamReader;
import com.example.camai_app.network.dto.AlertCreateRequest;
import com.example.camai_app.network.dto.ApiMessageResponse;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends AppCompatActivity {

    private enum SourceMode {
        LOCAL_CAMERA,
        IP_CAMERA
    }

    private static final String CHANNEL_ID = "cam_ai_alert_channel";
    private static final int NOTIFY_ID = 1001;
    private static final float PERSON_CONFIDENCE_THRESHOLD = 0.55f;
    private static final long PERSON_LOST_TIMEOUT_MS = 1500L;

    private static final String PREF_CAMERA_STATE = "camera_state";
    private static final String KEY_LAST_SOURCE = "last_source";
    private static final String KEY_LAST_URL = "last_url";
    private static final String KEY_AUTO_RECONNECT = "auto_reconnect";

    private PreviewView previewView;
    private ImageView ipPreviewImage;
    private OverlayView overlayView;
    private TextView statusText;

    private EditText ipUrlInput;
    private Button btnLocalCam;
    private Button btnIpCam;
    private Button btnConnectCamera;
    private Button btnDisconnectCamera;
    private BottomNavigationView bottomNav;
    private MaterialToolbar topBar;

    private ExecutorService cameraExecutor;
    private Yolo11Detector yolo11Detector;
    private ProcessCameraProvider cameraProvider;
    private AlertDbHelper alertDbHelper;
    private SessionManager sessionManager;
    private AuthService authService;

    private final Deque<String> eventLogs = new ArrayDeque<>();
    private final AtomicBoolean isAnalyzing = new AtomicBoolean(false);

    private SourceMode currentMode = SourceMode.LOCAL_CAMERA;
    private boolean pendingConnectLocalCamera = false;
    private boolean isCameraConnected = false;
    private boolean restoreAttempted = false;
    private boolean navigatingToHistory = false;

    private Thread mjpegThread;
    private volatile boolean isMjpegRunning = false;
    private HttpURLConnection mjpegConnection;
    private MjpegStreamReader mjpegReader;
    private int mjpegFrameCounter = 0;

    private boolean personCurrentlyVisible = false;
    private long lastSeenPersonTime = 0L;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean cameraGranted = Boolean.TRUE.equals(result.get(Manifest.permission.CAMERA));
                boolean notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                        || Boolean.TRUE.equals(result.get(Manifest.permission.POST_NOTIFICATIONS));

                if (!notificationGranted) {
                    addLog("Không có quyền thông báo, app chỉ hiện trạng thái trên màn hình.");
                }

                if (pendingConnectLocalCamera) {
                    pendingConnectLocalCamera = false;
                    if (cameraGranted) {
                        connectLocalCamera();
                    } else {
                        statusText.setText("Thiếu quyền camera. Không thể kết nối Cam máy.");
                        addLog("Người dùng từ chối quyền camera.");
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sessionManager = new SessionManager(this);
        if (!sessionManager.isLoggedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);
        bindViews();

        alertDbHelper = new AlertDbHelper(this);
        authService = ApiClient.get().create(AuthService.class);
        cameraExecutor = Executors.newSingleThreadExecutor();

        try {
            yolo11Detector = new Yolo11Detector(this, PERSON_CONFIDENCE_THRESHOLD);
            addLog("Đã khởi tạo YOLOv11 detector.");
        } catch (Exception e) {
            statusText.setText("Không thể tải model YOLOv11: " + e.getMessage());
            addLog("Lỗi khởi tạo model: " + e.getMessage());
        }

        createNotificationChannel();
        restoreSavedState();
        setupUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        maybeReconnect();
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (navigatingToHistory) {
            navigatingToHistory = false;
            return;
        }

        if (isCameraConnected) {
            saveReconnectState(true);
            disconnectAllSourcesInternal(false, false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnectAllSourcesInternal(false, false);

        if (yolo11Detector != null) {
            yolo11Detector.close();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }

    private void bindViews() {
        topBar = findViewById(R.id.topBar);
        previewView = findViewById(R.id.previewView);
        ipPreviewImage = findViewById(R.id.ipPreviewImage);
        overlayView = findViewById(R.id.overlayView);
        statusText = findViewById(R.id.statusText);
        ipUrlInput = findViewById(R.id.ipUrlInput);
        btnLocalCam = findViewById(R.id.btnLocalCam);
        btnIpCam = findViewById(R.id.btnIpCam);
        btnConnectCamera = findViewById(R.id.btnConnectCamera);
        btnDisconnectCamera = findViewById(R.id.btnDisconnectCamera);
        bottomNav = findViewById(R.id.bottomNav);
    }

    private void setupUi() {
        String userName = sessionManager.getUserName();
        if (!TextUtils.isEmpty(userName)) {
            topBar.setSubtitle("Xin chào, " + userName);
        }

        if (TextUtils.isEmpty(ipUrlInput.getText().toString().trim())) {
            ipUrlInput.setText("http://192.168.1.2:8080/stream.mjpg");
        }

        updateSourceUi();

        btnLocalCam.setOnClickListener(v -> {
            currentMode = SourceMode.LOCAL_CAMERA;
            saveCurrentSource();
            updateSourceUi();
            addLog("Đã chọn nguồn: Cam máy.");
        });

        btnIpCam.setOnClickListener(v -> {
            currentMode = SourceMode.IP_CAMERA;
            saveCurrentSource();
            updateSourceUi();
            addLog("Đã chọn nguồn: IP Cam.");
        });

        btnConnectCamera.setOnClickListener(v -> {
            saveCurrentSource();
            connectSelectedSource();
        });

        btnDisconnectCamera.setOnClickListener(v -> {
            saveReconnectState(false);
            disconnectAllSources();
        });

        bottomNav.setSelectedItemId(R.id.nav_camera);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();

            if (id == R.id.nav_camera) {
                return true;
            }

            if (id == R.id.nav_history) {
                navigatingToHistory = true;
                startActivity(new Intent(this, HistoryActivity.class));
                overridePendingTransition(0, 0);
                return true;
            }

            if (id == R.id.nav_logout) {
                saveReconnectState(false);
                disconnectAllSources();
                sessionManager.clear();
                startActivity(new Intent(this, LoginActivity.class));
                finishAffinity();
                return true;
            }

            return false;
        });
    }

    private void updateSourceUi() {
        if (currentMode == SourceMode.LOCAL_CAMERA) {
            ipUrlInput.setVisibility(View.GONE);
            statusText.setText(isCameraConnected
                    ? "Đang dùng Cam máy."
                    : "Đã chọn Cam máy. Nhấn \"Kết nối Camera\" để bắt đầu.");
        } else {
            ipUrlInput.setVisibility(View.VISIBLE);
            statusText.setText(isCameraConnected
                    ? "Đang dùng IP Cam."
                    : "Đã chọn IP Cam. Nhập URL MJPEG rồi nhấn \"Kết nối Camera\".");
        }
    }

    private void connectSelectedSource() {
        if (yolo11Detector == null) {
            statusText.setText("Model chưa sẵn sàng.");
            return;
        }

        if (currentMode == SourceMode.LOCAL_CAMERA) {
            ensurePermissionsAndConnectLocal();
        } else {
            connectIpCameraMjpeg();
        }
    }

    private void ensurePermissionsAndConnectLocal() {
        boolean hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;

        boolean hasNotification = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;

        if (hasCamera && hasNotification) {
            connectLocalCamera();
            return;
        }

        pendingConnectLocalCamera = true;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.POST_NOTIFICATIONS
            });
        } else {
            permissionLauncher.launch(new String[]{Manifest.permission.CAMERA});
        }
    }

    private void connectLocalCamera() {
        disconnectAllSourcesInternal(false, false);

        previewView.setVisibility(View.VISIBLE);
        ipPreviewImage.setVisibility(View.GONE);
        overlayView.setVisibility(View.VISIBLE);
        overlayView.clear();

        statusText.setText("Đang kết nối Cam máy...");
        addLog("Bắt đầu kết nối Cam máy.");

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeLocalFrame);

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

                isCameraConnected = true;
                saveReconnectState(true);
                updateSourceUi();
                addLog("Cam máy đã kết nối thành công.");
            } catch (Exception e) {
                statusText.setText("Không thể mở camera: " + e.getMessage());
                addLog("Lỗi mở camera: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeLocalFrame(@NonNull ImageProxy imageProxy) {
        if (currentMode != SourceMode.LOCAL_CAMERA || yolo11Detector == null || !isCameraConnected) {
            imageProxy.close();
            return;
        }

        if (!isAnalyzing.compareAndSet(false, true)) {
            imageProxy.close();
            return;
        }

        Bitmap bitmap = null;
        try {
            bitmap = imageProxyToBitmap(imageProxy);
            if (bitmap == null) {
                runOnUiThread(() -> statusText.setText("Không đọc được frame từ Cam máy."));
                return;
            }

            List<DetectionResult> detections = yolo11Detector.detect(bitmap);
            final Bitmap finalBitmap = bitmap;

            runOnUiThread(() -> {
                overlayView.setDetections(detections, finalBitmap.getWidth(), finalBitmap.getHeight());
                handleDetectionResult(detections, "Cam máy", finalBitmap, 0.85);
            });

        } catch (Exception e) {
            runOnUiThread(() -> {
                statusText.setText("AI lỗi: " + e.getMessage());
                addLog("Lỗi YOLOv11: " + e.getMessage());
            });
        } finally {
            imageProxy.close();
            isAnalyzing.set(false);
        }
    }

    private void connectIpCameraMjpeg() {
        String streamUrl = ipUrlInput.getText().toString().trim();
        if (TextUtils.isEmpty(streamUrl)) {
            statusText.setText("Vui lòng nhập URL IP Cam.");
            return;
        }

        disconnectAllSourcesInternal(false, false);

        previewView.setVisibility(View.GONE);
        ipPreviewImage.setVisibility(View.VISIBLE);
        overlayView.setVisibility(View.VISIBLE);
        overlayView.clear();

        statusText.setText("Đang kết nối MJPEG IP Cam...");
        addLog("Kết nối MJPEG: " + streamUrl);

        isCameraConnected = true;
        isMjpegRunning = true;
        mjpegFrameCounter = 0;
        saveReconnectState(true);

        mjpegThread = new Thread(() -> {
            try {
                URL url = new URL(streamUrl);
                mjpegConnection = (HttpURLConnection) url.openConnection();
                mjpegConnection.setConnectTimeout(5000);
                mjpegConnection.setReadTimeout(15000);
                mjpegConnection.setRequestMethod("GET");
                mjpegConnection.connect();

                int responseCode = mjpegConnection.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    runOnUiThread(() -> {
                        statusText.setText("IP Cam lỗi HTTP: " + responseCode);
                        addLog("MJPEG HTTP lỗi: " + responseCode);
                    });
                    return;
                }

                InputStream inputStream = mjpegConnection.getInputStream();
                mjpegReader = new MjpegStreamReader(inputStream);

                runOnUiThread(() -> {
                    updateSourceUi();
                    addLog("MJPEG stream đã kết nối.");
                });

                while (isMjpegRunning && isCameraConnected) {
                    Bitmap frame = mjpegReader.readFrame();
                    if (frame == null) {
                        runOnUiThread(() -> {
                            statusText.setText("Không đọc được frame từ MJPEG.");
                            addLog("MJPEG trả về frame null.");
                        });
                        break;
                    }

                    runOnUiThread(() -> ipPreviewImage.setImageBitmap(frame));

                    mjpegFrameCounter++;
                    if (mjpegFrameCounter % 8 == 0
                            && yolo11Detector != null
                            && isAnalyzing.compareAndSet(false, true)) {
                        try {
                            List<DetectionResult> detections = yolo11Detector.detect(frame);

                            runOnUiThread(() -> {
                                ipPreviewImage.setImageBitmap(frame);
                                overlayView.setDetections(detections, frame.getWidth(), frame.getHeight());
                                handleDetectionResult(detections, "IP Cam", frame, 0.85);
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> {
                                statusText.setText("AI lỗi: " + e.getMessage());
                                addLog("YOLO MJPEG lỗi: " + e.getMessage());
                            });
                        } finally {
                            isAnalyzing.set(false);
                        }
                    }
                }

            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusText.setText("Lỗi MJPEG: " + e.getMessage());
                    addLog("MJPEG lỗi: " + e.getMessage());
                });
            } finally {
                stopMjpegStreamInternal();
            }
        });

        mjpegThread.start();
    }

    private void handleDetectionResult(
            @NonNull List<DetectionResult> detections,
            @NonNull String sourceLabel,
            Bitmap snapshotBitmap,
            double confidence
    ) {
        long now = System.currentTimeMillis();
        boolean personDetected = !detections.isEmpty();

        if (personDetected) {
            lastSeenPersonTime = now;

            DetectionResult best = detections.get(0);
            String label = best.getLabel();
            if (TextUtils.isEmpty(label)) {
                label = "Người";
            }

            if (!personCurrentlyVisible) {
                personCurrentlyVisible = true;

                String timeText = new SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss",
                        Locale.getDefault()
                ).format(new Date(now));

                statusText.setText(sourceLabel + ": phát hiện " + label + " ✅");

                String savedImagePath = "";
                if (snapshotBitmap != null) {
                    savedImagePath = saveSnapshot(snapshotBitmap, now);
                }

                String storedSource = sourceLabel + " - " + label;
                alertDbHelper.insertAlert(timeText, savedImagePath, storedSource);
                uploadAlertToBackend(storedSource, confidence, timeText, savedImagePath);
                sendLocalNotification(sourceLabel, label);
                addLog(sourceLabel + " phát hiện " + label + " lúc " + timeText);
            } else {
                statusText.setText(sourceLabel + ": đang có " + label + " trong khung hình.");
            }
            return;
        }

        if (personCurrentlyVisible && (now - lastSeenPersonTime > PERSON_LOST_TIMEOUT_MS)) {
            personCurrentlyVisible = false;
            statusText.setText(sourceLabel + ": người đã rời khỏi khung hình.");
            addLog(sourceLabel + ": người đã rời khỏi khung hình.");
        } else {
            statusText.setText(sourceLabel + ": chưa phát hiện người.");
        }
    }

    private String saveSnapshot(Bitmap bitmap, long ts) {
        try {
            File dir = new File(getFilesDir(), "snapshots");
            if (!dir.exists()) {
                dir.mkdirs();
            }

            File out = new File(dir, "alert_" + ts + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos);
            }

            return out.getAbsolutePath();
        } catch (Exception e) {
            addLog("Lưu ảnh lỗi: " + e.getMessage());
            return "";
        }
    }

    private void uploadAlertToBackend(String sourceLabel, double confidence, String detectedAt, String imagePath) {
        int userId = sessionManager.getUserId();
        if (userId <= 0 || authService == null) {
            return;
        }

        AlertCreateRequest body = new AlertCreateRequest(
                userId,
                null,
                imagePath,
                sourceLabel,
                confidence,
                detectedAt
        );

        authService.createAlert(body).enqueue(new Callback<ApiMessageResponse>() {
            @Override
            public void onResponse(Call<ApiMessageResponse> call, Response<ApiMessageResponse> response) {
                if (!response.isSuccessful()) {
                    addLog("Upload alert backend thất bại: HTTP " + response.code());
                }
            }

            @Override
            public void onFailure(Call<ApiMessageResponse> call, Throwable t) {
                addLog("Upload alert backend lỗi mạng: " + t.getMessage());
            }
        });
    }

    private void sendLocalNotification(String sourceLabel, String label) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("CamAI Alert")
                .setContentText(sourceLabel + ": phát hiện " + label)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            addLog("Không thể gửi notification vì thiếu quyền.");
            return;
        }

        NotificationManagerCompat.from(this).notify(NOTIFY_ID, builder.build());
    }

    private void disconnectAllSources() {
        disconnectAllSourcesInternal(true, true);
    }

    private void disconnectAllSourcesInternal(boolean fromUserAction, boolean clearReconnectFlag) {
        stopLocalCamera();
        stopMjpegStreamInternal();

        isCameraConnected = false;
        personCurrentlyVisible = false;
        lastSeenPersonTime = 0L;

        if (overlayView != null) {
            overlayView.clear();
        }

        if (currentMode == SourceMode.IP_CAMERA) {
            ipPreviewImage.setImageBitmap(null);
        }

        updateSourceUi();

        if (clearReconnectFlag) {
            saveReconnectState(false);
        }

        if (fromUserAction) {
            statusText.setText("Đã ngắt kết nối camera.");
            addLog("Người dùng đã ngắt kết nối camera.");
        }
    }

    private void stopLocalCamera() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }

    private void stopMjpegStreamInternal() {
        isMjpegRunning = false;

        if (mjpegReader != null) {
            mjpegReader.close();
            mjpegReader = null;
        }

        if (mjpegConnection != null) {
            mjpegConnection.disconnect();
            mjpegConnection = null;
        }

        if (mjpegThread != null) {
            mjpegThread.interrupt();
            mjpegThread = null;
        }
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private Bitmap imageProxyToBitmap(@NonNull ImageProxy imageProxy) {
        try {
            Image image = imageProxy.getImage();
            if (image == null) {
                return null;
            }

            byte[] nv21 = yuv420ToNv21(imageProxy);
            YuvImage yuvImage = new YuvImage(
                    nv21,
                    ImageFormat.NV21,
                    imageProxy.getWidth(),
                    imageProxy.getHeight(),
                    null
            );

            byte[] imageBytes;
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                yuvImage.compressToJpeg(
                        new Rect(0, 0, imageProxy.getWidth(), imageProxy.getHeight()),
                        90,
                        out
                );
                imageBytes = out.toByteArray();
            }

            Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
            if (bitmap == null) {
                return null;
            }

            Matrix matrix = new Matrix();
            matrix.postRotate(imageProxy.getImageInfo().getRotationDegrees());

            return Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    bitmap.getWidth(),
                    bitmap.getHeight(),
                    matrix,
                    true
            );
        } catch (Exception e) {
            addLog("Convert ImageProxy lỗi: " + e.getMessage());
            return null;
        }
    }

    private byte[] yuv420ToNv21(@NonNull ImageProxy image) {
        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);
        return nv21;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "CamAI Alerts",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Thông báo khi camera phát hiện người");

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void addLog(String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        eventLogs.addFirst("[" + time + "] " + message);

        while (eventLogs.size() > 4) {
            eventLogs.removeLast();
        }
    }

    private void restoreSavedState() {
        SharedPreferences prefs = getSharedPreferences(PREF_CAMERA_STATE, MODE_PRIVATE);

        String source = prefs.getString(KEY_LAST_SOURCE, "LOCAL");
        String url = prefs.getString(KEY_LAST_URL, "http://192.168.1.2:8080/stream.mjpg");

        currentMode = "IP".equals(source) ? SourceMode.IP_CAMERA : SourceMode.LOCAL_CAMERA;
        ipUrlInput.setText(url);
    }

    private void maybeReconnect() {
        if (restoreAttempted) {
            return;
        }
        restoreAttempted = true;

        SharedPreferences prefs = getSharedPreferences(PREF_CAMERA_STATE, MODE_PRIVATE);
        boolean shouldReconnect = prefs.getBoolean(KEY_AUTO_RECONNECT, false);

        if (!shouldReconnect || isCameraConnected) {
            return;
        }

        addLog("Đang khôi phục kết nối camera...");
        connectSelectedSource();
    }

    private void saveCurrentSource() {
        SharedPreferences prefs = getSharedPreferences(PREF_CAMERA_STATE, MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_LAST_SOURCE, currentMode == SourceMode.IP_CAMERA ? "IP" : "LOCAL")
                .putString(KEY_LAST_URL, ipUrlInput.getText().toString().trim())
                .apply();
    }

    private void saveReconnectState(boolean shouldReconnect) {
        SharedPreferences prefs = getSharedPreferences(PREF_CAMERA_STATE, MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_LAST_SOURCE, currentMode == SourceMode.IP_CAMERA ? "IP" : "LOCAL")
                .putString(KEY_LAST_URL, ipUrlInput.getText().toString().trim())
                .putBoolean(KEY_AUTO_RECONNECT, shouldReconnect)
                .apply();
    }
}