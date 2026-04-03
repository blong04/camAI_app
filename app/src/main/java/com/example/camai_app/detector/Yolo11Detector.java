package com.example.camai_app.detector;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageProxy;

import org.tensorflow.lite.Interpreter;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

public class Yolo11Detector {

    private static final String MODEL_PATH = "yolo11n_float32.tflite";
    private static final int PIXEL_SIZE = 3;

    private final Interpreter interpreter;
    private final int inputWidth;
    private final int inputHeight;
    private final float personThreshold;

    public Yolo11Detector(@NonNull Context context, float personThreshold) throws IOException {
        this.personThreshold = personThreshold;
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(4);
        interpreter = new Interpreter(loadModelFile(context, MODEL_PATH), options);

        int[] inputShape = interpreter.getInputTensor(0).shape();
        inputHeight = inputShape[1];
        inputWidth = inputShape[2];
    }

    public boolean detectPerson(@NonNull ImageProxy imageProxy) {
        Bitmap bitmap = toBitmap(imageProxy);
        if (bitmap == null) {
            return false;
        }

        Bitmap resized = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true);
        ByteBuffer input = convertBitmapToInputBuffer(resized);

        int[] outputShape = interpreter.getOutputTensor(0).shape();
        int channels = outputShape[1];
        int anchors = outputShape[2];
        float[][][] output = new float[1][channels][anchors];

        interpreter.run(input, output);

        int personClassIndex = 0;
        int classStart = 4;
        if (channels <= classStart + personClassIndex) {
            return false;
        }

        for (int i = 0; i < anchors; i++) {
            float personScore = output[0][classStart + personClassIndex][i];
            if (personScore >= personThreshold) {
                return true;
            }
        }

        return false;
    }

    public void close() {
        interpreter.close();
    }

    private static MappedByteBuffer loadModelFile(Context context, String modelPath) throws IOException {
        try (AssetFileDescriptor fileDescriptor = context.getAssets().openFd(modelPath);
             FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor())) {
            FileChannel fileChannel = inputStream.getChannel();
            long startOffset = fileDescriptor.getStartOffset();
            long declaredLength = fileDescriptor.getDeclaredLength();
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        }
    }

    private static ByteBuffer convertBitmapToInputBuffer(Bitmap bitmap) {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4 * bitmap.getWidth() * bitmap.getHeight() * PIXEL_SIZE);
        byteBuffer.order(ByteOrder.nativeOrder());

        int[] intValues = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());

        int pixel = 0;
        for (int i = 0; i < bitmap.getHeight(); i++) {
            for (int j = 0; j < bitmap.getWidth(); j++) {
                int value = intValues[pixel++];
                byteBuffer.putFloat(((value >> 16) & 0xFF) / 255f);
                byteBuffer.putFloat(((value >> 8) & 0xFF) / 255f);
                byteBuffer.putFloat((value & 0xFF) / 255f);
            }
        }

        byteBuffer.rewind();
        return byteBuffer;
    }

    private static Bitmap toBitmap(ImageProxy imageProxy) {
        Image image = imageProxy.getImage();
        if (image == null) {
            return null;
        }

        byte[] nv21 = yuv420ToNv21(imageProxy);
        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, imageProxy.getWidth(), imageProxy.getHeight(), null);
        byte[] imageBytes;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            yuvImage.compressToJpeg(new Rect(0, 0, imageProxy.getWidth(), imageProxy.getHeight()), 90, out);
            imageBytes = out.toByteArray();
        } catch (IOException e) {
            return null;
        }

        Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
        if (bitmap == null) {
            return null;
        }

        Matrix matrix = new Matrix();
        matrix.postRotate(imageProxy.getImageInfo().getRotationDegrees());

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    private static byte[] yuv420ToNv21(ImageProxy image) {
        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];

        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        return nv21;
    }
}