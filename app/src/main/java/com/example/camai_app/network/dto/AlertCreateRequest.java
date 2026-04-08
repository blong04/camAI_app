package com.example.camai_app.network.dto;

import com.google.gson.annotations.SerializedName;

public class AlertCreateRequest {
    @SerializedName("user_id")
    public int userId;

    @SerializedName("camera_id")
    public Integer cameraId;

    @SerializedName("image_url")
    public String imageUrl;

    @SerializedName("source_label")
    public String sourceLabel;

    public double confidence;

    @SerializedName("detected_at")
    public String detectedAt;

    public AlertCreateRequest(int userId, Integer cameraId, String imageUrl, String sourceLabel, double confidence, String detectedAt) {
        this.userId = userId;
        this.cameraId = cameraId;
        this.imageUrl = imageUrl;
        this.sourceLabel = sourceLabel;
        this.confidence = confidence;
        this.detectedAt = detectedAt;
    }
}