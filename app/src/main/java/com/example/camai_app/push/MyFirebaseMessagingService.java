package com.example.camai_app.push;

import android.util.Log;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class MyFirebaseMessagingService extends FirebaseMessagingService {
    @Override
    public void onNewToken(String token) {
        Log.d("FCM_TOKEN", token);
        // TODO: gửi token này lên Laravel để gắn với user
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        Log.d("FCM_MSG", "From server: " + message.getData());
        // TODO: tạo local notification khi server push
    }
}