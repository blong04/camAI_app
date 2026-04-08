package com.example.camai_app.history;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class AlertDbHelper extends SQLiteOpenHelper {
    public AlertDbHelper(Context context) {
        super(context, "camai.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE alerts(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "time_text TEXT," +
                "image_path TEXT," +
                "source TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    public void insertAlert(String timeText, String imagePath, String source) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("time_text", timeText);
        cv.put("image_path", imagePath);
        cv.put("source", source);
        db.insert("alerts", null, cv);
    }

    public List<String> getAllSimple() {
        List<String> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT time_text,source,image_path FROM alerts ORDER BY id DESC", null);
        while (c.moveToNext()) {
            out.add(c.getString(0) + " | " + c.getString(1) + "\n" + c.getString(2));
        }
        c.close();
        return out;
    }
}