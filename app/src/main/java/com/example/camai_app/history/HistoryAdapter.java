package com.example.camai_app.history;

import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.camai_app.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {

    private final List<AlertItem> items = new ArrayList<>();

    public void submit(List<AlertItem> data) {
        items.clear();
        if (data != null) {
            items.addAll(data);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_alert, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        AlertItem item = items.get(position);

        holder.tvTime.setText(item.getTimeText());

        ParsedSource parsed = parseSourceAndLabel(item.getSource());
        holder.tvSource.setText("Nguồn: " + parsed.source);
        holder.tvLabel.setText("Phát hiện: " + parsed.label);

        if (isUnknownLabel(parsed.label)) {
            holder.tvLabel.setTextColor(Color.parseColor("#DC2626"));
        } else {
            holder.tvLabel.setTextColor(Color.parseColor("#16A34A"));
        }

        bindThumbnail(holder, item.getImagePath());
    }

    private void bindThumbnail(@NonNull VH holder, String imagePath) {
        if (!TextUtils.isEmpty(imagePath)) {
            File file = new File(imagePath);
            if (file.exists()) {
                holder.ivThumb.setImageBitmap(BitmapFactory.decodeFile(file.getAbsolutePath()));
                return;
            }
        }
        holder.ivThumb.setImageResource(android.R.drawable.ic_menu_report_image);
    }

    private ParsedSource parseSourceAndLabel(String rawSource) {
        if (TextUtils.isEmpty(rawSource)) {
            return new ParsedSource("Không rõ", "Người");
        }

        String cleaned = rawSource.trim();

        if (cleaned.contains(" - ")) {
            String[] parts = cleaned.split(" - ", 2);
            String source = parts[0].trim();
            String label = parts.length > 1 ? parts[1].trim() : "Người";

            if (TextUtils.isEmpty(source)) {
                source = "Không rõ";
            }
            if (TextUtils.isEmpty(label)) {
                label = "Người";
            }

            return new ParsedSource(source, label);
        }

        return new ParsedSource(cleaned, "Người");
    }

    private boolean isUnknownLabel(String label) {
        if (label == null) {
            return false;
        }

        String normalized = label.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("người lạ")
                || normalized.equals("nguoi la")
                || normalized.equals("unknown")
                || normalized.equals("stranger");
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivThumb;
        TextView tvTime;
        TextView tvLabel;
        TextView tvSource;

        VH(@NonNull View itemView) {
            super(itemView);
            ivThumb = itemView.findViewById(R.id.ivThumb);
            tvTime = itemView.findViewById(R.id.tvTime);
            tvLabel = itemView.findViewById(R.id.tvLabel);
            tvSource = itemView.findViewById(R.id.tvSource);
        }
    }

    private static class ParsedSource {
        final String source;
        final String label;

        ParsedSource(String source, String label) {
            this.source = source;
            this.label = label;
        }
    }
}