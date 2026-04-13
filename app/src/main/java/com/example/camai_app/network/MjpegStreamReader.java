package com.example.camai_app.network;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class MjpegStreamReader {

    private final BufferedInputStream input;

    public MjpegStreamReader(InputStream inputStream) {
        this.input = new BufferedInputStream(inputStream, 32 * 1024);
    }

    @Nullable
    public Bitmap readFrame() throws IOException {
        int prev = -1;
        int cur;

        // Tìm đầu JPEG: FF D8
        while (true) {
            cur = input.read();
            if (cur == -1) return null;

            if (prev == 0xFF && cur == 0xD8) {
                break;
            }
            prev = cur;
        }

        ByteArrayOutputStream jpegBuffer = new ByteArrayOutputStream();
        jpegBuffer.write(0xFF);
        jpegBuffer.write(0xD8);

        prev = -1;

        // Đọc đến cuối JPEG: FF D9
        while (true) {
            cur = input.read();
            if (cur == -1) return null;

            jpegBuffer.write(cur);

            if (prev == 0xFF && cur == 0xD9) {
                break;
            }
            prev = cur;
        }

        byte[] jpegBytes = jpegBuffer.toByteArray();
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
    }

    public void close() {
        try {
            input.close();
        } catch (IOException ignored) {
        }
    }
}