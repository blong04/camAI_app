package com.example.camai_app.history;

import android.graphics.BitmapFactory;
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

public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {

    private final List<AlertItem> items = new ArrayList<>();

    public void submit(List<AlertItem> data) {
        items.clear();
        if (data != null) items.addAll(data);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_alert, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        AlertItem item = items.get(position);

        holder.tvTime.setText(item.getTimeText());
        holder.tvSource.setText("Nguồn: " + item.getSource());

        if (!TextUtils.isEmpty(item.getImagePath())) {
            File f = new File(item.getImagePath());
            if (f.exists()) {
                holder.ivThumb.setImageBitmap(BitmapFactory.decodeFile(f.getAbsolutePath()));
                return;
            }
        }
        holder.ivThumb.setImageResource(android.R.drawable.ic_menu_report_image);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivThumb;
        TextView tvTime;
        TextView tvSource;

        VH(@NonNull View itemView) {
            super(itemView);
            ivThumb = itemView.findViewById(R.id.ivThumb);
            tvTime = itemView.findViewById(R.id.tvTime);
            tvSource = itemView.findViewById(R.id.tvSource);
        }
    }
}