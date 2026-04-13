package com.example.camai_app.detector;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

public class Yolo11Detector {

    private static final String MODEL_PATH = "yolo11n_float32.tflite";

    private final Interpreter interpreter;
    private final int inputSize = 640;

    public Yolo11Detector(Context context, float threshold) throws IOException {
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(4);
        interpreter = new Interpreter(loadModelFile(context), options);
    }

    private MappedByteBuffer loadModelFile(Context context) throws IOException {
        AssetFileDescriptor fileDescriptor = context.getAssets().openFd(MODEL_PATH);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY,
                fileDescriptor.getStartOffset(),
                fileDescriptor.getDeclaredLength());
    }

    public List<DetectionResult> detect(Bitmap bitmap) {
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true);

        ByteBuffer input = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4);
        input.order(ByteOrder.nativeOrder());

        int[] pixels = new int[inputSize * inputSize];
        resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize);

        for (int p : pixels) {
            input.putFloat(((p >> 16) & 0xFF) / 255f);
            input.putFloat(((p >> 8) & 0xFF) / 255f);
            input.putFloat((p & 0xFF) / 255f);
        }

        float[][][] output = new float[1][84][8400];
        interpreter.run(input, output);

        List<DetectionResult> results = new ArrayList<>();

        for (int i = 0; i < 8400; i++) {
            float conf = output[0][4][i];

            if (conf > 0.6f) {
                float cx = output[0][0][i];
                float cy = output[0][1][i];
                float w = output[0][2][i];
                float h = output[0][3][i];

                float left = (cx - w / 2f) * bitmap.getWidth() / inputSize;
                float top = (cy - h / 2f) * bitmap.getHeight() / inputSize;
                float right = (cx + w / 2f) * bitmap.getWidth() / inputSize;
                float bottom = (cy + h / 2f) * bitmap.getHeight() / inputSize;

                results.add(new DetectionResult(
                        left, top, right, bottom,
                        conf,
                        0,
                        "Người"
                ));
            }
        }

        return results;
    }

    public boolean detectPerson(Bitmap bitmap) {
        return !detect(bitmap).isEmpty();
    }

    public void close() {
        interpreter.close();
    }
}