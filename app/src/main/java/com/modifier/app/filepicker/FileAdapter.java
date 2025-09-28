
package com.modifier.app.filepicker;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.modifier.app.R;

import java.util.List;

public class FileAdapter extends RecyclerView.Adapter<FileAdapter.FileViewHolder> {

    private List<FileItem> fileItems;
    private OnFileClickListener listener;

    public interface OnFileClickListener {
        void onFileClick(FileItem fileItem);
    }

    public FileAdapter(List<FileItem> fileItems, OnFileClickListener listener) {
        this.fileItems = fileItems;
        this.listener = listener;
    }

    @NonNull
    @Override
    public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_file, parent, false);
        return new FileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
        FileItem fileItem = fileItems.get(position);
        holder.nameTextView.setText(fileItem.getName());
        holder.infoTextView.setText(fileItem.getLastModifiedString());
        
        // Set appropriate icon based on file type
        if (fileItem.isDirectory()) {
            holder.iconImageView.setImageResource(R.drawable.ic_folder);
        } else if (isApkFile(fileItem)) {
            // Use Android logo for APK files if available, otherwise use file icon
            // You can replace ic_android with ic_file if you don't have an Android logo
            holder.iconImageView.setImageResource(R.drawable.ic_android);
        } else {
            // Fallback for other file types (shouldn't happen with current filtering)
            holder.iconImageView.setImageResource(R.drawable.ic_file);
        }
        
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onFileClick(fileItem);
            }
        });
    }

    @Override
    public int getItemCount() {
        return fileItems.size();
    }

    public void updateData(List<FileItem> newData) {
        this.fileItems = newData;
        notifyDataSetChanged();
    }

    /**
     * Check if a FileItem represents an APK file
     */
    private boolean isApkFile(FileItem fileItem) {
        return !fileItem.isDirectory() && fileItem.getName().toLowerCase().endsWith(".apk");
    }

    static class FileViewHolder extends RecyclerView.ViewHolder {
        ImageView iconImageView;
        TextView nameTextView;
        TextView infoTextView;

        public FileViewHolder(@NonNull View itemView) {
            super(itemView);
            iconImageView = itemView.findViewById(R.id.icon_image_view);
            nameTextView = itemView.findViewById(R.id.name_text_view);
            infoTextView = itemView.findViewById(R.id.info_text_view);
        }
    }
}

