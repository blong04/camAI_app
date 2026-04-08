package com.example.camai_app.history;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.camai_app.R;

public class HistoryActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        ListView lv = findViewById(R.id.rvHistory);
        AlertDbHelper db = new AlertDbHelper(this);
        lv.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, db.getAllSimple()));
    }
}