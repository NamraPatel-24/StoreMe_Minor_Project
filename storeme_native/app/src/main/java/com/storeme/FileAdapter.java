package com.storeme;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Enterprise Material 3 FileAdapter supporting rich metadata badges,
 * 44dp category-tinted icon containers, and RBAC permission indicators.
 */
public class FileAdapter extends RecyclerView.Adapter<FileAdapter.ViewHolder> {
    private JSONArray filesArray = new JSONArray();
    private OnFileClickListener listener;
    private String userRole = "viewer";

    public interface OnFileClickListener {
        void onFileClick(JSONObject file);
        void onMenuClick(View anchor, JSONObject file);
    }

    public FileAdapter(OnFileClickListener listener) {
        this.listener = listener;
    }

    public void setFiles(JSONArray files) {
        this.filesArray = files != null ? files : new JSONArray();
        notifyDataSetChanged();
    }

    public void setUserRole(String role) {
        this.userRole = role != null ? role.trim().toLowerCase() : "viewer";
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_file, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        try {
            JSONObject file = filesArray.getJSONObject(position);
            Context ctx = holder.itemView.getContext();
            boolean isDir = file.optBoolean("isDir");
            boolean isBack = file.optBoolean("isBack");
            String name = file.optString("name", "");

            holder.textName.setText(name);

            if (isBack) {
                holder.textDetails.setText("Parent Directory");
                if (holder.chipDate != null) holder.chipDate.setVisibility(View.GONE);
                if (holder.chipRole != null) holder.chipRole.setVisibility(View.GONE);
                holder.btnAction.setVisibility(View.GONE);
                applyIconStyle(ctx, holder, R.drawable.ic_folder, R.color.file_folder, R.color.file_folder_bg);
            } else if (isDir) {
                holder.textDetails.setText("Directory");
                if (holder.chipDate != null) holder.chipDate.setVisibility(View.GONE);
                if (holder.chipRole != null) {
                    if ("viewer".equals(userRole)) {
                        holder.chipRole.setText(R.string.read_only);
                        holder.chipRole.setBackgroundResource(R.drawable.bg_badge_viewer);
                        holder.chipRole.setTextColor(ContextCompat.getColor(ctx, R.color.badge_viewer_fg));
                        holder.chipRole.setVisibility(View.VISIBLE);
                    } else {
                        holder.chipRole.setVisibility(View.GONE);
                    }
                }
                holder.btnAction.setVisibility(View.VISIBLE);
                applyIconStyle(ctx, holder, R.drawable.ic_folder, R.color.file_folder, R.color.file_folder_bg);
            } else {
                long bytes = file.optLong("size", 0);
                holder.textDetails.setText(formatFileSize(bytes));

                long modTime = file.optLong("lastModified", file.optLong("modified", 0));
                if (holder.chipDate != null) {
                    if (modTime > 0) {
                        SimpleDateFormat sdf = new SimpleDateFormat("MMM d, yyyy", Locale.US);
                        holder.chipDate.setText(sdf.format(new Date(modTime)));
                        holder.chipDate.setVisibility(View.VISIBLE);
                    } else {
                        holder.chipDate.setVisibility(View.GONE);
                    }
                }

                if (holder.chipRole != null) {
                    String owner = file.optString("owner", "");
                    if ("viewer".equals(userRole)) {
                        holder.chipRole.setText(R.string.read_only);
                        holder.chipRole.setBackgroundResource(R.drawable.bg_badge_viewer);
                        holder.chipRole.setTextColor(ContextCompat.getColor(ctx, R.color.badge_viewer_fg));
                        holder.chipRole.setVisibility(View.VISIBLE);
                    } else if (!owner.isEmpty()) {
                        holder.chipRole.setText(owner);
                        holder.chipRole.setBackgroundResource(R.drawable.bg_badge_uploader);
                        holder.chipRole.setTextColor(ContextCompat.getColor(ctx, R.color.badge_uploader_fg));
                        holder.chipRole.setVisibility(View.VISIBLE);
                    } else {
                        holder.chipRole.setVisibility(View.GONE);
                    }
                }

                holder.btnAction.setVisibility(View.VISIBLE);
                bindCategoryIcon(ctx, holder, name.toLowerCase());
            }

            holder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onFileClick(file);
            });
            holder.btnAction.setOnClickListener(v -> {
                if (listener != null) listener.onMenuClick(v, file);
            });
        } catch (Exception e) {
            android.util.Log.e("FileAdapter", "Bind error at " + position + ": " + e.getMessage(), e);
        }
    }

    private void bindCategoryIcon(Context ctx, ViewHolder holder, String lower) {
        if (lower.endsWith(".pdf")) {
            applyIconStyle(ctx, holder, R.drawable.ic_file_pdf, R.color.file_pdf, R.color.file_pdf_bg);
        } else if (lower.endsWith(".doc") || lower.endsWith(".docx")) {
            applyIconStyle(ctx, holder, R.drawable.ic_file_word, R.color.file_doc, R.color.file_doc_bg);
        } else if (lower.endsWith(".xls") || lower.endsWith(".xlsx") || lower.endsWith(".csv")) {
            applyIconStyle(ctx, holder, R.drawable.ic_file_excel, R.color.file_sheet, R.color.file_sheet_bg);
        } else if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) {
            applyIconStyle(ctx, holder, R.drawable.ic_file_powerpoint, R.color.file_slide, R.color.file_slide_bg);
        } else if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".json") ||
                   lower.endsWith(".xml") || lower.endsWith(".java") || lower.endsWith(".py") ||
                   lower.endsWith(".js") || lower.endsWith(".html") || lower.endsWith(".css")) {
            applyIconStyle(ctx, holder, R.drawable.ic_file_text, R.color.file_code, R.color.file_code_bg);
        } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                   lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".svg")) {
            applyIconStyle(ctx, holder, R.drawable.ic_image, R.color.file_image, R.color.file_image_bg);
        } else if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi") || lower.endsWith(".mov")) {
            applyIconStyle(ctx, holder, R.drawable.ic_video, R.color.file_video, R.color.file_video_bg);
        } else if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".aac") || lower.endsWith(".flac")) {
            applyIconStyle(ctx, holder, R.drawable.ic_audio, R.color.file_audio, R.color.file_audio_bg);
        } else if (lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".tar") || lower.endsWith(".gz") || lower.endsWith(".7z")) {
            applyIconStyle(ctx, holder, R.drawable.ic_archive, R.color.file_archive, R.color.file_archive_bg);
        } else {
            applyIconStyle(ctx, holder, R.drawable.ic_document, R.color.file_default, R.color.file_default_bg);
        }
    }

    private void applyIconStyle(Context ctx, ViewHolder holder, int iconRes, int iconColorRes, int bgColorRes) {
        holder.imgIcon.setImageResource(iconRes);
        holder.imgIcon.setColorFilter(ContextCompat.getColor(ctx, iconColorRes));
        if (holder.iconContainer != null) {
            GradientDrawable gd = new GradientDrawable();
            gd.setCornerRadius(10 * ctx.getResources().getDisplayMetrics().density);
            gd.setColor(ContextCompat.getColor(ctx, bgColorRes));
            gd.setStroke((int)(1 * ctx.getResources().getDisplayMetrics().density), ContextCompat.getColor(ctx, R.color.border_subtle));
            holder.iconContainer.setBackground(gd);
        }
    }

    private String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes >= 1024 * 1024 * 1024) {
            return String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        } else if (bytes >= 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        } else if (bytes >= 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        } else {
            return bytes + " B";
        }
    }

    @Override
    public int getItemCount() {
        return filesArray.length();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView textName, textDetails, chipDate, chipRole;
        ImageView imgIcon, btnAction;
        FrameLayout iconContainer;

        public ViewHolder(View itemView) {
            super(itemView);
            textName = itemView.findViewById(R.id.textName);
            textDetails = itemView.findViewById(R.id.textDetails);
            chipDate = itemView.findViewById(R.id.chipDate);
            chipRole = itemView.findViewById(R.id.chipRole);
            btnAction = itemView.findViewById(R.id.btnAction);
            imgIcon = itemView.findViewById(R.id.imgIcon);
            iconContainer = itemView.findViewById(R.id.iconContainer);
        }
    }
}
