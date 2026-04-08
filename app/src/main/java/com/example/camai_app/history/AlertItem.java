package com.example.camai_app.history;

public class AlertItem {
    private final long id;
    private final String timeText;
    private final String source;
    private final String imagePath;

    public AlertItem(long id, String timeText, String source, String imagePath) {
        this.id = id;
        this.timeText = timeText;
        this.source = source;
        this.imagePath = imagePath;
    }

    public long getId() {
        return id;
    }

    public String getTimeText() {
        return timeText;
    }

    public String getSource() {
        return source;
    }

    public String getImagePath() {
        return imagePath;
    }
}