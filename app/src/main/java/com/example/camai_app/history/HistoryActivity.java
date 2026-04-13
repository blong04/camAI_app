package com.example.camai_app.history;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.camai_app.MainActivity;
import com.example.camai_app.R;
import com.example.camai_app.auth.LoginActivity;
import com.example.camai_app.auth.SessionManager;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.List;

public class HistoryActivity extends AppCompatActivity {

    private RecyclerView rvHistory;
    private TextView tvCount;
    private TextView tvEmpty;
    private BottomNavigationView bottomNav;

    private AlertDbHelper dbHelper;
    private SessionManager sessionManager;
    private HistoryAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sessionManager = new SessionManager(this);
        if (!sessionManager.isLoggedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_history);

        rvHistory = findViewById(R.id.rvHistory);
        tvCount = findViewById(R.id.tvCount);
        tvEmpty = findViewById(R.id.tvEmpty);
        bottomNav = findViewById(R.id.bottomNav);

        dbHelper = new AlertDbHelper(this);

        rvHistory.setLayoutManager(new LinearLayoutManager(this));
        adapter = new HistoryAdapter();
        rvHistory.setAdapter(adapter);

        loadHistory();
        setupBottomNav();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadHistory();
    }

    private void loadHistory() {
        List<AlertItem> items = dbHelper.getAllAlerts();
        adapter.submit(items);

        tvCount.setText(items.size() + " bản ghi");
        tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void setupBottomNav() {
        if (bottomNav == null) return;

        bottomNav.setSelectedItemId(R.id.nav_history);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();

            if (id == R.id.nav_camera) {
                finish();
                overridePendingTransition(0, 0);
                return true;
            }

            if (id == R.id.nav_history) {
                return true;
            }

            if (id == R.id.nav_logout) {
                sessionManager.clear();
                startActivity(new Intent(this, LoginActivity.class));
                finishAffinity();
                return true;
            }

            return false;
        });
    }
}