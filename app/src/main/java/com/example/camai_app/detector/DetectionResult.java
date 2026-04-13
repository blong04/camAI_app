package com.example.camai_app.detector;

public class DetectionResult {
    private final float left;
    private final float top;
    private final float right;
    private final float bottom;
    private final float confidence;
    private final int classIndex;
    private final String label;

    public DetectionResult(
            float left,
            float top,
            float right,
            float bottom,
            float confidence,
            int classIndex,
            String label
    ) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.confidence = confidence;
        this.classIndex = classIndex;
        this.label = label;
    }

    public float getLeft() {
        return left;
    }

    public float getTop() {
        return top;
    }

    public float getRight() {
        return right;
    }

    public float getBottom() {
        return bottom;
    }

    public float getConfidence() {
        return confidence;
    }

    public int getClassIndex() {
        return classIndex;
    }

    public String getLabel() {
        return label;
    }

    public float getWidth() {
        return right - left;
    }

    public float getHeight() {
        return bottom - top;
    }

    public DetectionResult withLabel(String newLabel) {
        return new DetectionResult(
                left, top, right, bottom, confidence, classIndex, newLabel
        );
    }
}