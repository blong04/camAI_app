package com.example.camai_app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
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

import com.example.camai_app.detector.Yolo11Detector;
import com.google.common.util.concurrent.ListenableFuture;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private static final String CHANNEL_ID = "cam_ai_alert_channel";
    private static final int NOTIFY_ID = 1001;
    private static final long NOTIFY_COOLDOWN_MS = 15_000L;
    private static final float PERSON_CONFIDENCE_THRESHOLD = 0.55f;

    private PreviewView previewView;
    private TextView statusText;
    private TextView logText;

    private ExecutorService cameraExecutor;
    private Yolo11Detector yolo11Detector;

    private final Deque<String> eventLogs = new ArrayDeque<>();
    private long lastNotifyTime = 0L;
    private final AtomicBoolean isAnalyzing = new AtomicBoolean(false);

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean cameraGranted = Boolean.TRUE.equals(result.get(Manifest.permission.CAMERA));
                boolean notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                        || Boolean.TRUE.equals(result.get(Manifest.permission.POST_NOTIFICATIONS));

                if (cameraGranted) {
                    statusText.setText("Camera đã sẵn sàng. Đang nhận diện người bằng YOLOv11...");
                    startCamera();
                } else {
                    statusText.setText("Thiếu quyền camera. Không thể chạy demo.");
                }

                if (!notificationGranted) {
                    addLog("Không có quyền thông báo, app chỉ hiển thị trạng thái tại màn hình.");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        statusText = findViewById(R.id.statusText);
        logText = findViewById(R.id.logText);

        cameraExecutor = Executors.newSingleThreadExecutor();

        try {
            yolo11Detector = new Yolo11Detector(this, PERSON_CONFIDENCE_THRESHOLD);
            addLog("Đã khởi tạo YOLOv11 TFLite detector.");
        } catch (Exception e) {
            statusText.setText("Không thể tải model YOLOv11: " + e.getMessage());
            addLog("Lỗi khởi tạo model: " + e.getMessage());
            return;
        }

        createNotificationChannel();
        requestPermissionsAndStart();
    }

    private void requestPermissionsAndStart() {
        boolean hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;

        boolean hasNotification = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;

        if (hasCamera && hasNotification) {
            statusText.setText("Camera đã sẵn sàng. Đang nhận diện người bằng YOLOv11...");
            startCamera();
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

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
                addLog("Đã bind camera và YOLOv11 detector.");
            } catch (Exception e) {
                statusText.setText("Không thể mở camera: " + e.getMessage());
                addLog("Lỗi mở camera: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFrame(@NonNull ImageProxy imageProxy) {
        if (yolo11Detector == null) {
            imageProxy.close();
            return;
        }

        if (!isAnalyzing.compareAndSet(false, true)) {
            imageProxy.close();
            return;
        }

        try {
            boolean personDetected = yolo11Detector.detectPerson(imageProxy);
            runOnUiThread(() -> onYoloResult(personDetected));
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

    private void onYoloResult(boolean personDetected) {
        if (personDetected) {
            long now = System.currentTimeMillis();
            statusText.setText("YOLOv11: Phát hiện người ✅");

            if (now - lastNotifyTime > NOTIFY_COOLDOWN_MS) {
                lastNotifyTime = now;
                sendLocalNotification();
                addLog("YOLOv11 phát hiện người - đã gửi thông báo local.");
            }
        } else {
            statusText.setText("YOLOv11: Chưa phát hiện người...");
        }
    }

    private void sendLocalNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("CamAI Alert")
                .setContentText("YOLOv11 phát hiện người trong camera")
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

        logText.post(() -> logText.setText(TextUtils.join("\n", eventLogs)));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (yolo11Detector != null) {
            yolo11Detector.close();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}