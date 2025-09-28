
package com.modifier.app.filepicker;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Environment;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.modifier.app.R;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Stack;

public class FilePickerDialog extends DialogFragment implements FileAdapter.OnFileClickListener {

    private TextView pathTextView;
    private RecyclerView recyclerView;
    private FileAdapter adapter;
    private File currentDirectory;
    private File selectedFile;
    private FileSelectedListener listener;
    private Button selectButton;
    private ImageButton backButton;
    private ImageButton upButton;
    
    // Navigation history for better back navigation
    private Stack<File> navigationHistory;
    private File rootDirectory;

    public interface FileSelectedListener {
        void onFileSelected(File file);
    }

    public static FilePickerDialog newInstance() {
        return new FilePickerDialog();
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        try {
            listener = (FileSelectedListener) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context + " must implement FileSelectedListener");
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        
        // Handle hardware back button
        dialog.setOnKeyListener((dialogInterface, keyCode, keyEvent) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && keyEvent.getAction() == KeyEvent.ACTION_UP) {
                return handleBackNavigation();
            }
            return false;
        });
        
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_file_picker, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Initialize views
        pathTextView = view.findViewById(R.id.path_text_view);
        recyclerView = view.findViewById(R.id.file_list_recycler_view);
        Button cancelButton = view.findViewById(R.id.cancel_button);
        selectButton = view.findViewById(R.id.select_button);
        backButton = view.findViewById(R.id.back_button);
        upButton = view.findViewById(R.id.up_button);

        // Initialize navigation
        navigationHistory = new Stack<>();
        rootDirectory = Environment.getExternalStorageDirectory();

        // Setup RecyclerView
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new FileAdapter(new ArrayList<>(), this);
        recyclerView.setAdapter(adapter);

        // Initialize with the root directory
        currentDirectory = rootDirectory;
        navigateToDirectory(currentDirectory, false);

        // Setup button listeners
        cancelButton.setOnClickListener(v -> dismiss());
        
        selectButton.setOnClickListener(v -> {
            if (selectedFile != null && !selectedFile.isDirectory() && selectedFile.getName().toLowerCase().endsWith(".apk")) {
                listener.onFileSelected(selectedFile);
                dismiss();
            } else {
                Toast.makeText(requireContext(), "Please select an APK file", Toast.LENGTH_SHORT).show();
            }
        });

        // Back button - go to previous directory in history
        backButton.setOnClickListener(v -> handleBackNavigation());

        // Up button - go to parent directory
        upButton.setOnClickListener(v -> navigateToParentDirectory());

        // Make path clickable for breadcrumb navigation
        pathTextView.setOnClickListener(v -> showPathNavigationDialog());

        // Initially disable select button until a file is selected
        selectButton.setEnabled(false);
        updateNavigationButtons();
    }

    /**
     * Navigate to a directory with optional history tracking
     */
    private void navigateToDirectory(File directory, boolean addToHistory) {
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            Toast.makeText(requireContext(), "Cannot access directory", Toast.LENGTH_SHORT).show();
            return;
        }

        // Add current directory to history before navigating (if requested)
        if (addToHistory && currentDirectory != null && !currentDirectory.equals(directory)) {
            navigationHistory.push(currentDirectory);
        }

        currentDirectory = directory;
        pathTextView.setText(getDisplayPath(directory));
        
        File[] files = directory.listFiles();
        List<FileItem> fileItems = new ArrayList<>();
        
        if (files != null) {
            // Sort: directories first, then files, both alphabetically
            Arrays.sort(files, (f1, f2) -> {
                if (f1.isDirectory() && !f2.isDirectory()) {
                    return -1;
                } else if (!f1.isDirectory() && f2.isDirectory()) {
                    return 1;
                } else {
                    return f1.getName().compareToIgnoreCase(f2.getName());
                }
            });
            
            for (File file : files) {
                // Skip hidden files
                if (file.isHidden()) {
                    continue;
                }
                
                // Only show directories and APK files
                if (file.isDirectory() || isApkFile(file)) {
                    fileItems.add(new FileItem(file));
                }
            }
        }
        
        // Show message if no APK files or directories found
        if (fileItems.isEmpty()) {
            Toast.makeText(requireContext(), "No APK files or folders found in this directory", Toast.LENGTH_SHORT).show();
        }
        
        adapter.updateData(fileItems);
        
        // Reset selected file when navigating
        selectedFile = null;
        selectButton.setEnabled(false);
        updateNavigationButtons();
    }

    /**
     * Handle back navigation (history-based)
     */
    private boolean handleBackNavigation() {
        if (!navigationHistory.isEmpty()) {
            File previousDirectory = navigationHistory.pop();
            navigateToDirectory(previousDirectory, false);
            return true; // Consumed the back press
        } else if (!isAtRoot()) {
            // If no history but not at root, go to parent
            navigateToParentDirectory();
            return true;
        } else {
            // At root with no history, allow dialog to close
            return false;
        }
    }

    /**
     * Navigate to parent directory
     */
    private void navigateToParentDirectory() {
        if (currentDirectory != null && !isAtRoot()) {
            File parentDirectory = currentDirectory.getParentFile();
            if (parentDirectory != null && parentDirectory.canRead()) {
                navigateToDirectory(parentDirectory, true);
            } else {
                Toast.makeText(requireContext(), "Cannot access parent directory", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(requireContext(), "Already at root directory", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Show path navigation dialog for breadcrumb navigation
     */
    private void showPathNavigationDialog() {
        if (currentDirectory == null) return;

        // Create list of path components
        List<File> pathComponents = new ArrayList<>();
        File current = currentDirectory;
        
        while (current != null && !current.equals(rootDirectory.getParentFile())) {
            pathComponents.add(0, current); // Add to beginning
            current = current.getParentFile();
        }

        if (pathComponents.isEmpty()) return;

        // Create simple dialog with path options
        String[] pathNames = new String[pathComponents.size()];
        for (int i = 0; i < pathComponents.size(); i++) {
            File dir = pathComponents.get(i);
            if (dir.equals(rootDirectory)) {
                pathNames[i] = "📱 Storage";
            } else {
                pathNames[i] = "📁 " + dir.getName();
            }
        }

        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(requireContext());
        builder.setTitle("Navigate to:")
                .setItems(pathNames, (dialog, which) -> {
                    File selectedDir = pathComponents.get(which);
                    if (!selectedDir.equals(currentDirectory)) {
                        navigateToDirectory(selectedDir, true);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Update navigation button states
     */
    private void updateNavigationButtons() {
        boolean canGoBack = !navigationHistory.isEmpty() || !isAtRoot();
        boolean canGoUp = !isAtRoot();
        
        backButton.setEnabled(canGoBack);
        upButton.setEnabled(canGoUp);
        
        // Update button appearance
        backButton.setAlpha(canGoBack ? 1.0f : 0.5f);
        upButton.setAlpha(canGoUp ? 1.0f : 0.5f);
    }

    /**
     * Check if current directory is at root
     */
    private boolean isAtRoot() {
        return currentDirectory != null && currentDirectory.equals(rootDirectory);
    }

    /**
     * Get display-friendly path
     */
    private String getDisplayPath(File directory) {
        if (directory == null) return "";
        
        String path = directory.getAbsolutePath();
        String rootPath = rootDirectory.getAbsolutePath();
        
        if (path.equals(rootPath)) {
            return "📱 Internal Storage";
        } else if (path.startsWith(rootPath)) {
            return "📱 " + path.substring(rootPath.length());
        } else {
            return path;
        }
    }

    /**
     * Check if a file is an APK file based on its extension
     */
    private boolean isApkFile(File file) {
        return !file.isDirectory() && file.getName().toLowerCase().endsWith(".apk");
    }

    @Override
    public void onFileClick(FileItem fileItem) {
        if (fileItem.isDirectory()) {
            navigateToDirectory(fileItem.getFile(), true);
        } else {
            if (isApkFile(fileItem.getFile())) {
                selectedFile = fileItem.getFile();
                selectButton.setEnabled(true);
                Toast.makeText(requireContext(), "APK selected: " + fileItem.getName(), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(requireContext(), "Please select an APK file", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null) {
            int width = ViewGroup.LayoutParams.MATCH_PARENT;
            int height = ViewGroup.LayoutParams.WRAP_CONTENT;
            Objects.requireNonNull(dialog.getWindow()).setLayout(width, height);
        }
    }
}

