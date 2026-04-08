package com.example.camai_app.network.dto;

import com.google.gson.annotations.SerializedName;

public class AuthResponse {
    public String token;

    @SerializedName("user_id")
    public int userId;

    public String name;
    public String message;
}