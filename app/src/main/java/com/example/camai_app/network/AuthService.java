package com.example.camai_app.network;

import com.example.camai_app.network.dto.AlertCreateRequest;
import com.example.camai_app.network.dto.ApiMessageResponse;
import com.example.camai_app.network.dto.AuthResponse;
import com.example.camai_app.network.dto.LoginRequest;
import com.example.camai_app.network.dto.RegisterRequest;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;

public interface AuthService {
    @POST("register.php")
    Call<AuthResponse> register(@Body RegisterRequest body);

    @POST("login.php")
    Call<AuthResponse> login(@Body LoginRequest body);

    @POST("alerts_create.php")
    Call<ApiMessageResponse> createAlert(@Body AlertCreateRequest body);
}