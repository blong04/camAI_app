package com.example.camai_app.network.dto;

import com.google.gson.annotations.SerializedName;

public class ApiMessageResponse {
    public String message;

    @SerializedName("alert_id")
    public long alertId;
}