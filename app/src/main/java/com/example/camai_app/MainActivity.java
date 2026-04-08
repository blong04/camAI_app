package com.example.camai_app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
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
import com.example.camai_app.detector.Yolo11Detector;
import com.example.camai_app.history.AlertDbHelper;
import com.example.camai_app.network.ApiClient;
import com.example.camai_app.network.AuthService;
import com.example.camai_app.network.dto.AlertCreateRequest;
import com.example.camai_app.network.dto.ApiMessageResponse;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends AppCompatActivity {

    private enum SourceMode { LOCAL_CAMERA, IP_CAMERA }

    private static final String CHANNEL_ID = "cam_ai_alert_channel";
    private static final int NOTIFY_ID = 1001;
    private static final long NOTIFY_COOLDOWN_MS = 15_000L;
    private static final float PERSON_CONFIDENCE_THRESHOLD = 0.55f;

    private PreviewView previewView;
    private ImageView ipPreviewImage;
    private TextView statusText;
    private TextView logText;
    private EditText ipUrlInput;
    private Button btnLocalCam;
    private Button btnIpCam;

    private ExecutorService cameraExecutor;
    private ScheduledExecutorService ipExecutor;
    private ScheduledFuture<?> ipPollingTask;

    private Yolo11Detector yolo11Detector;
    private ProcessCameraProvider cameraProvider;

    private AlertDbHelper alertDbHelper;
    private SessionManager sessionManager;
    private AuthService authService;

    private final Deque<String> eventLogs = new ArrayDeque<>();
    private long lastNotifyTime = 0L;
    private final AtomicBoolean isAnalyzing = new AtomicBoolean(false);
    private SourceMode currentMode = SourceMode.LOCAL_CAMERA;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean cameraGranted = Boolean.TRUE.equals(result.get(Manifest.permission.CAMERA));
                boolean notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                        || Boolean.TRUE.equals(result.get(Manifest.permission.POST_NOTIFICATIONS));

                if (!notificationGranted) {
                    addLog("Không có quyền thông báo, app chỉ hiển thị trạng thái tại màn hình.");
                }

                if (cameraGranted) {
                    switchToLocalCameraMode();
                } else {
                    statusText.setText("Thiếu quyền camera. Chuyển sang IP Cam nếu cần.");
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

        previewView = findViewById(R.id.previewView);
        ipPreviewImage = findViewById(R.id.ipPreviewImage);
        statusText = findViewById(R.id.statusText);
        logText = findViewById(R.id.logText);
        ipUrlInput = findViewById(R.id.ipUrlInput);
        btnLocalCam = findViewById(R.id.btnLocalCam);
        btnIpCam = findViewById(R.id.btnIpCam);

        ipUrlInput.setText("http://192.168.1.2:8080/shot.jpg");

        alertDbHelper = new AlertDbHelper(this);
        authService = ApiClient.get().create(AuthService.class);

        cameraExecutor = Executors.newSingleThreadExecutor();
        ipExecutor = Executors.newSingleThreadScheduledExecutor();

        try {
            yolo11Detector = new Yolo11Detector(this, PERSON_CONFIDENCE_THRESHOLD);
            addLog("Đã khởi tạo YOLOv11 TFLite detector.");
        } catch (Exception e) {
            statusText.setText("Không thể tải model YOLOv11: " + e.getMessage());
            addLog("Lỗi khởi tạo model: " + e.getMessage());
            return;
        }

        createNotificationChannel();
        setupButtons();
        requestPermissionsAndStart();
    }

    private void setupButtons() {
        btnLocalCam.setOnClickListener(v -> switchToLocalCameraMode());
        btnIpCam.setOnClickListener(v -> switchToIpCameraMode());
    }

    private void requestPermissionsAndStart() {
        boolean hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;

        boolean hasNotification = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;

        if (hasCamera && hasNotification) {
            switchToLocalCameraMode();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.POST_NOTIFICATIONS
            });
        } else {
            permissionLauncher.launch(new String[]{Manifest.permission.CAMERA});
        }
    }

    private void switchToLocalCameraMode() {
        currentMode = SourceMode.LOCAL_CAMERA;
        stopIpPolling();

        previewView.setVisibility(View.VISIBLE);
        ipPreviewImage.setVisibility(View.GONE);
        statusText.setText("Mode Cam máy: Đang nhận diện người bằng YOLOv11...");
        addLog("Đã chuyển sang mode camera máy.");
        startCamera();
    }

    private void switchToIpCameraMode() {
        currentMode = SourceMode.IP_CAMERA;
        stopLocalCamera();

        previewView.setVisibility(View.GONE);
        ipPreviewImage.setVisibility(View.VISIBLE);

        String ipUrl = ipUrlInput.getText().toString().trim();
        if (TextUtils.isEmpty(ipUrl)) {
            statusText.setText("Mode IP Cam: Thiếu URL ảnh (ví dụ /shot.jpg)");
            return;
        }

        statusText.setText("Mode IP Cam: Đang kết nối " + ipUrl);
        addLog("Đã chuyển sang mode IP camera.");
        startIpPolling(ipUrl);
    }

    private void startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            statusText.setText("Thiếu quyền camera. Không thể mở cam máy.");
            return;
        }

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
                addLog("Đã bind camera máy và YOLOv11 detector.");
            } catch (Exception e) {
                statusText.setText("Không thể mở camera: " + e.getMessage());
                addLog("Lỗi mở camera: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void stopLocalCamera() {
        if (cameraProvider != null) cameraProvider.unbindAll();
    }

    private void startIpPolling(String snapshotUrl) {
        stopIpPolling();

        ipPollingTask = ipExecutor.scheduleWithFixedDelay(() -> {
            if (currentMode != SourceMode.IP_CAMERA || yolo11Detector == null) return;
            if (!isAnalyzing.compareAndSet(false, true)) return;

            try {
                Bitmap bitmap = fetchSnapshotBitmap(snapshotUrl);
                if (bitmap == null) {
                    runOnUiThread(() -> statusText.setText("Mode IP Cam: Không lấy được ảnh từ URL"));
                    return;
                }

                boolean personDetected = yolo11Detector.detectPerson(bitmap);
                runOnUiThread(() -> {
                    ipPreviewImage.setImageBitmap(bitmap);
                    onYoloResult(personDetected, "IP Cam", bitmap, 0.85);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusText.setText("Mode IP Cam lỗi: " + e.getMessage());
                    addLog("Lỗi IP camera: " + e.getMessage());
                });
            } finally {
                isAnalyzing.set(false);
            }
        }, 0, 900, TimeUnit.MILLISECONDS);
    }

    private void stopIpPolling() {
        if (ipPollingTask != null) {
            ipPollingTask.cancel(true);
            ipPollingTask = null;
        }
    }

    private Bitmap fetchSnapshotBitmap(String snapshotUrl) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(snapshotUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            connection.setRequestMethod("GET");

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;

            try (InputStream inputStream = connection.getInputStream()) {
                return BitmapFactory.decodeStream(inputStream);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void analyzeFrame(@NonNull ImageProxy imageProxy) {
        if (currentMode != SourceMode.LOCAL_CAMERA || yolo11Detector == null) {
            imageProxy.close();
            return;
        }

        if (!isAnalyzing.compareAndSet(false, true)) {
            imageProxy.close();
            return;
        }

        try {
            boolean personDetected = yolo11Detector.detectPerson(imageProxy);
            runOnUiThread(() -> onYoloResult(personDetected, "Cam máy", null, 0.85));
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

    private void onYoloResult(boolean personDetected, String sourceLabel, Bitmap snapshotBitmap, double confidence) {
        if (!personDetected) {
            statusText.setText(sourceLabel + ": YOLOv11 chưa phát hiện người...");
            return;
        }

        long now = System.currentTimeMillis();
        String timeText = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(now));
        statusText.setText(sourceLabel + ": YOLOv11 phát hiện người ✅");

        String savedImagePath = "";
        if (snapshotBitmap != null) {
            savedImagePath = saveSnapshot(snapshotBitmap, now);
        }

        // SQLite local
        alertDbHelper.insertAlert(timeText, savedImagePath, sourceLabel);

        // Backend API
        uploadAlertToBackend(sourceLabel, confidence, timeText, savedImagePath);

        if (now - lastNotifyTime > NOTIFY_COOLDOWN_MS) {
            lastNotifyTime = now;
            sendLocalNotification(sourceLabel);
            addLog(sourceLabel + " phát hiện người lúc " + timeText);
        }
    }

    private String saveSnapshot(Bitmap bitmap, long ts) {
        try {
            File dir = new File(getFilesDir(), "snapshots");
            if (!dir.exists()) dir.mkdirs();

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
        if (userId <= 0 || authService == null) return;

        AlertCreateRequest body = new AlertCreateRequest(
                userId,
                null,
                imagePath, // demo local path, có thể đổi sang URL upload thật
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

    private void sendLocalNotification(String sourceLabel) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("CamAI Alert")
                .setContentText(sourceLabel + ": phát hiện người trong camera")
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

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "CamAI Alerts",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Thông báo khi camera phát hiện người");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void addLog(String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        eventLogs.addFirst("[" + time + "] " + message);

        while (eventLogs.size() > 4) eventLogs.removeLast();

        logText.post(() -> logText.setText(TextUtils.join("\n", eventLogs)));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopIpPolling();
        stopLocalCamera();

        if (yolo11Detector != null) yolo11Detector.close();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (ipExecutor != null) ipExecutor.shutdown();
    }
}