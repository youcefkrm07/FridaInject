
package com.modifier.app.filepicker;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FileItem {
    private File file;
    private String name;
    private boolean isDirectory;
    private long lastModified;

    public FileItem(File file) {
        this.file = file;
        this.name = file.getName();
        this.isDirectory = file.isDirectory();
        this.lastModified = file.lastModified();
    }

    public File getFile() {
        return file;
    }

    public String getName() {
        return name;
    }

    public boolean isDirectory() {
        return isDirectory;
    }

    public String getLastModifiedString() {
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy, hh:mm a", Locale.getDefault());
        return "Last edited: " + sdf.format(new Date(lastModified));
    }

    public String getPath() {
        return file.getAbsolutePath();
    }

    /**
     * Check if this file item represents an APK file
     */
    public boolean isApkFile() {
        return !isDirectory && name.toLowerCase().endsWith(".apk");
    }

    /**
     * Get file size in human readable format
     */
    public String getFileSizeString() {
        if (isDirectory) {
            return "Folder";
        }
        
        long bytes = file.length();
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format(Locale.getDefault(), "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
}

