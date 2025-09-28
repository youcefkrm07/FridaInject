package com.modifier.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.android.apksig.ApkSigner;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.snackbar.Snackbar;
import com.modifier.app.filepicker.FilePickerDialog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements FilePickerDialog.FileSelectedListener {
    private static final String TAG = "ModifierApp";
    private static final int REQUEST_CODE_SAVE_APK = 102;
    private static final int REQUEST_STORAGE_PERMISSION = 1001;
    
    // Keystore configuration
    private static final String KEYSTORE_PASSWORD = "android";
    private static final String KEY_ALIAS = "androiddebugkey";
    private static final String KEY_PASSWORD = "android";
    private static final String KEYSTORE_ASSET_NAME = "debug.keystore";

    private View rootView;
    private Button buttonSelectInput;
    private Button buttonProcess;
    private TextView textViewInputPath;
    private TextView textViewStatus;
    private ProgressBar progressBar;
    private MaterialCardView cardInputSelection;
    private MaterialCardView cardProcessing;

    private Uri inputApkUri;
    private File tempProcessedFile;
    private List<ApkSigner.SignerConfig> signerConfigs;
    private ApkProcessor apkProcessor;
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());
    
    private final ApkProcessor.ProgressListener progressListener = status -> {
        mainThreadHandler.post(() -> {
            textViewStatus.setText("Status: " + status);
            Log.d(TAG, "Progress update: " + status);
        });
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Set up toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        
        // Add subtitle with developer name
        if (getSupportActionBar() != null) {
            getSupportActionBar().setSubtitle("by " + getString(R.string.developer_name));
        }

        // Find views by ID
        rootView = findViewById(android.R.id.content);
        buttonSelectInput = findViewById(R.id.buttonSelectInput);
        buttonProcess = findViewById(R.id.buttonProcess);
        textViewInputPath = findViewById(R.id.textViewInputPath);
        textViewStatus = findViewById(R.id.textViewStatus);
        progressBar = findViewById(R.id.progressBar);
        cardInputSelection = findViewById(R.id.cardInputSelection);
        cardProcessing = findViewById(R.id.cardProcessing);

        // Initialize ApkProcessor
        apkProcessor = new ApkProcessor(getApplicationContext());

        // Set up event listeners
        buttonSelectInput.setOnClickListener(v -> selectInputApk());
        buttonProcess.setOnClickListener(v -> startApkProcessing());

        // Load signing configuration
        signerConfigs = loadSignerConfiguration();
        if (signerConfigs == null) {
            Log.e(TAG, "FATAL: Keystore could not be loaded. Processing disabled.");
            textViewStatus.setText("Status: ERROR - Keystore load failed!");
            showErrorMessage("Critical error: Signing keystore could not be loaded. App functionality limited.");
            buttonProcess.setEnabled(false);
            buttonSelectInput.setEnabled(false);
        } else {
            Log.i(TAG, "Signer configuration loaded successfully.");
            textViewStatus.setText(getString(R.string.ready_to_process));
        }

        checkProcessButtonState();
        resetInputSelectionUI();
        resetProcessingCardUI();
        
        // Request storage permissions if needed
        checkAndRequestPermissions();
    }
    
    private void checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                != PackageManager.PERMISSION_GRANTED) {
            
            ActivityCompat.requestPermissions(this, 
                new String[] {
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, 
                REQUEST_STORAGE_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permissions granted, we can proceed
                Log.i(TAG, "Storage permissions granted");
            } else {
                // Permission denied
                showErrorMessage("Storage permissions are required to select and save APK files");
                Log.e(TAG, "Storage permissions denied");
            }
        }
    }

    private void selectInputApk() {
        // Check if we have storage permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                != PackageManager.PERMISSION_GRANTED) {
            checkAndRequestPermissions();
            return;
        }
        
        // Show our custom file picker
        FilePickerDialog filePickerDialog = FilePickerDialog.newInstance();
        filePickerDialog.show(getSupportFragmentManager(), "file_picker");
    }
    
    @Override
    public void onFileSelected(File file) {
        try {
            // Convert File to Uri
            Uri uri = Uri.fromFile(file);
            
            // Set the URI and update UI
            inputApkUri = uri;
            String fileName = file.getName();
            textViewInputPath.setText(fileName);
            cardInputSelection.setStrokeColor(ContextCompat.getColor(this, R.color.success_green));
            resetProcessingCardUI();
            checkProcessButtonState();
            
            Log.i(TAG, "Selected APK: " + file.getAbsolutePath());
            
            // Show a success toast
            Toast.makeText(this, "Selected: " + fileName, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Error handling selected APK", e);
            showErrorMessage("Error handling selected APK: " + e.getMessage());
        }
    }

    private void startApkProcessing() {
        if (inputApkUri == null) {
            showErrorMessage("Please select an input APK file first.");
            return;
        }
        if (signerConfigs == null || signerConfigs.isEmpty()) {
            showErrorMessage("Error: Signing configuration not loaded. Cannot process.");
            return;
        }

        setUiProcessing(true);
        textViewStatus.setText("Status: Starting processing...");

        backgroundExecutor.execute(() -> {
            ApkProcessor.ProcessingResult result = null;
            File tempOutputForThisJob = null;

            try {
                tempOutputForThisJob = createTempApkFile("signed_output_");
                if (tempOutputForThisJob == null) {
                    throw new IOException("Could not create temporary output file for signed APK.");
                }
                Log.i(TAG, "Processing APK -> Temp Output: " + tempOutputForThisJob.getAbsolutePath());

                // Use the simplified method call without the modConfig parameter
                result = apkProcessor.processAndSignApk(inputApkUri, tempOutputForThisJob, signerConfigs, progressListener);

            } catch (Exception e) {
                Log.e(TAG, "Exception during background APK processing setup", e);
                result = new ApkProcessor.ProcessingResult(false, "Setup Error: " + e.getMessage(), null, e);
            }

            final ApkProcessor.ProcessingResult finalResult = result;
            final File finalTempOutputForThisJob = tempOutputForThisJob;
            
            mainThreadHandler.post(() -> {
                if (finalResult != null && finalResult.success && finalResult.outputFile != null && finalResult.outputFile.exists()) {
                    tempProcessedFile = finalResult.outputFile;
                    textViewStatus.setText("Status: " + finalResult.message + " Ready to save.");
                    Snackbar.make(rootView, "Processing successful! Choose save location.", Snackbar.LENGTH_LONG)
                        .setBackgroundTint(ContextCompat.getColor(this, R.color.success_green))
                        .setTextColor(ContextCompat.getColor(this, R.color.white))
                        .show();
                    promptToSaveFolder();
                } else {
                    setUiProcessing(false);
                    String errorMessage = "Processing failed.";
                    if (finalResult != null) {
                        errorMessage = "Error: " + finalResult.message;
                        if (finalResult.exception != null) {
                            Log.e(TAG, "Processing Exception: ", finalResult.exception);
                        }
                    }
                    textViewStatus.setText("Status: Failed - " + errorMessage);
                    showErrorMessage(errorMessage);
                    
                    if (finalTempOutputForThisJob != null && finalTempOutputForThisJob.exists()) {
                        if (!finalTempOutputForThisJob.delete()) {
                            Log.w(TAG, "Failed to delete temporary output file after failed processing: " 
                                 + finalTempOutputForThisJob.getAbsolutePath());
                        }
                    }
                    tempProcessedFile = null;
                }
            });
        });
    }

    private void promptToSaveFolder() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.android.package-archive");
        
        // Suggest a file name based on the input
        String suggestedName;
        if (inputApkUri != null) {
            suggestedName = getFileNameFromUri(inputApkUri);
            if (suggestedName.toLowerCase().endsWith(".apk")) {
                suggestedName = suggestedName.substring(0, suggestedName.length() - 4) + "_modified.apk";
            } else {
                suggestedName = suggestedName + "_modified.apk";
            }
        } else {
            suggestedName = "modified.apk";
        }
        intent.putExtra(Intent.EXTRA_TITLE, suggestedName);
        
        try {
            startActivityForResult(intent, REQUEST_CODE_SAVE_APK);
        } catch (Exception e) {
            Log.e(TAG, "Error launching save dialog", e);
            showErrorMessage("Could not open save dialog: " + e.getMessage());
        }
    }

    private String getFileNameFromUri(Uri uri) {
        String result = uri.getLastPathSegment();
        if (result != null && result.contains("/")) {
            result = result.substring(result.lastIndexOf('/') + 1);
        }
        return result != null ? result : "Unknown";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_CODE_SAVE_APK) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                saveProcessedApkToUri(data.getData());
            } else {
                setUiProcessing(false);
                textViewStatus.setText("Status: Save location selection cancelled.");
                
                // Clean up the temp file if user cancels
                if (tempProcessedFile != null && tempProcessedFile.exists()) {
                    if (!tempProcessedFile.delete()) {
                        Log.w(TAG, "Failed to delete temp file after save cancelled: " + tempProcessedFile.getAbsolutePath());
                    }
                    tempProcessedFile = null;
                }
            }
        }
    }

    private void saveProcessedApkToUri(Uri destinationUri) {
        if (tempProcessedFile == null || !tempProcessedFile.exists()) {
            showErrorMessage("Error: Processed file not available.");
            setUiProcessing(false);
            return;
        }

        textViewStatus.setText("Status: Saving result...");
        
        backgroundExecutor.execute(() -> {
            boolean success = false;
            String errorMessage = "";
            
            try (InputStream in = new FileInputStream(tempProcessedFile);
                 OutputStream out = getContentResolver().openOutputStream(destinationUri)) {
                if (out == null) {
                    throw new IOException("Failed to open output stream");
                }
                
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                success = true;
            } catch (IOException e) {
                Log.e(TAG, "Error saving APK", e);
                errorMessage = e.getMessage();
            } finally {
                // Delete the temporary file
                if (tempProcessedFile != null && tempProcessedFile.exists()) {
                    if (!tempProcessedFile.delete()) {
                        Log.w(TAG, "Failed to delete temp file after saving: " + tempProcessedFile.getAbsolutePath());
                    }
                    tempProcessedFile = null;
                }
            }
            
            final boolean finalSuccess = success;
            final String finalErrorMessage = errorMessage;
            
            mainThreadHandler.post(() -> {
                setUiProcessing(false);
                if (finalSuccess) {
                    textViewStatus.setText("Status: Modified APK saved successfully.");
                    showSuccessMessage("APK saved successfully!");
                    resetInputSelectionUI();
                    // Reset input APK as well, requiring a fresh selection for next operation
                    inputApkUri = null;
                    textViewInputPath.setText("No APK selected");
                    checkProcessButtonState();
                } else {
                    textViewStatus.setText("Status: Failed to save Modified APK.");
                    showErrorMessage("Error saving file: " + finalErrorMessage);
                }
            });
        });
    }

    private List<ApkSigner.SignerConfig> loadSignerConfiguration() {
        try {
            KeyStore keystore = KeyStore.getInstance("PKCS12");
            
            try (InputStream keystoreInputStream = getAssets().open(KEYSTORE_ASSET_NAME)) {
                keystore.load(keystoreInputStream, KEYSTORE_PASSWORD.toCharArray());
            } catch (IOException e) {
                Log.e(TAG, "Failed to open keystore from assets. Check if file exists in assets folder.", e);
                showErrorMessage("Keystore file not found in assets. Did you add debug.keystore?");
                return null;
            }
            
            try {
                PrivateKey privateKey = (PrivateKey) keystore.getKey(KEY_ALIAS, KEY_PASSWORD.toCharArray());
                if (privateKey == null) {
                    Log.e(TAG, "Private key not found in keystore with alias: " + KEY_ALIAS);
                    showErrorMessage("Private key not found in keystore. Check alias and password.");
                    return null;
                }
                
                List<X509Certificate> certs = new ArrayList<>();
                X509Certificate cert = (X509Certificate) keystore.getCertificate(KEY_ALIAS);
                if (cert == null) {
                    Log.e(TAG, "Certificate not found in keystore");
                    showErrorMessage("Certificate not found in keystore. Check alias.");
                    return null;
                }
                certs.add(cert);
                
                return Arrays.asList(new ApkSigner.SignerConfig.Builder("CERT", privateKey, certs).build());
            } catch (Exception e) {
                Log.e(TAG, "Error accessing keystore entries", e);
                showErrorMessage("Error accessing keystore entries: " + e.getMessage());
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading signing configuration", e);
            showErrorMessage("Error loading keystore: " + e.getMessage());
            return null;
        }
    }

    private File createTempApkFile(String prefix) {
        try {
            File outputDir = getCacheDir();
            return File.createTempFile(prefix, ".apk", outputDir);
        } catch (IOException e) {
            Log.e(TAG, "Error creating temp file", e);
            return null;
        }
    }

    private void showErrorMessage(String message) {
        Snackbar.make(rootView, message, Snackbar.LENGTH_LONG)
            .setBackgroundTint(ContextCompat.getColor(this, R.color.error_red))
            .setTextColor(ContextCompat.getColor(this, R.color.white))
            .show();
    }

    private void showSuccessMessage(String message) {
        Snackbar.make(rootView, message, Snackbar.LENGTH_LONG)
            .setBackgroundTint(ContextCompat.getColor(this, R.color.success_green))
            .setTextColor(ContextCompat.getColor(this, R.color.white))
            .show();
    }

    private void checkProcessButtonState() {
        buttonProcess.setEnabled(inputApkUri != null && signerConfigs != null && !signerConfigs.isEmpty());
    }

    private void resetInputSelectionUI() {
        cardInputSelection.setStrokeColor(ContextCompat.getColor(this, R.color.divider));
    }

    private void resetProcessingCardUI() {
        cardProcessing.setStrokeColor(ContextCompat.getColor(this, R.color.divider));
        progressBar.setVisibility(View.INVISIBLE);
        progressBar.setProgress(0);
    }

    private void setUiProcessing(boolean processing) {
        boolean enableButtons = !processing && signerConfigs != null && !signerConfigs.isEmpty();
        buttonSelectInput.setEnabled(enableButtons);
        buttonProcess.setEnabled(enableButtons && inputApkUri != null);

        progressBar.setVisibility(processing ? View.VISIBLE : View.INVISIBLE);
        if (processing) {
            progressBar.setIndeterminate(true);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        backgroundExecutor.shutdown();
        
        // Clean up any temporary files
        if (tempProcessedFile != null && tempProcessedFile.exists()) {
            if (!tempProcessedFile.delete()) {
                Log.w(TAG, "Failed to delete temp file on destroy: " + tempProcessedFile.getAbsolutePath());
            }
        }
    }
}
