package com.example.camai_app.network.dto;

public class RegisterRequest {
    public String name;
    public String email;
    public String password;
    public String password_confirmation;

    public RegisterRequest(String name, String email, String password) {
        this.name = name;
        this.email = email;
        this.password = password;
        this.password_confirmation = password;
    }
}