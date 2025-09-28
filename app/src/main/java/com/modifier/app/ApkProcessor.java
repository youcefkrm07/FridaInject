
package com.modifier.app;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.android.apksig.ApkSigner;

import org.jf.baksmali.Baksmali;
import org.jf.baksmali.BaksmaliOptions;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.smali.Smali;
import org.jf.smali.SmaliOptions;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class ApkProcessor {
    private static final String TAG = "ApkProcessor";
    private final Context context;

    // Hardcoded values for modification
    private static final String TARGET_CLASS_PATH = "com/applisto/appcloner/classes/DefaultProvider";
    private static final String TARGET_METHOD_SIGNATURE = ".method public onCreate(Landroid/content/Context;)Z";
    private static final String REPLACEMENT_METHOD_SMALI =
        ".method public onCreate(Landroid/content/Context;)Z\n" +
        "    .registers 3\n" +
        "\n" +
        "    if-eqz p1, :cond_e\n" +
        "\n" +
        "    sget-boolean v0, Lcom/applisto/appcloner/classes/DefaultProvider;->sCreated:Z\n" +
        "\n" +
        "    if-nez v0, :cond_c\n" +
        "\n" +
        "    const/4 v0, 0x1\n" +
        "\n" +
        "    sput-boolean v0, Lcom/applisto/appcloner/classes/DefaultProvider;->sCreated:Z\n" +
        "\n" +
        "    invoke-virtual {p0, p1, p1}, Lcom/applisto/appcloner/classes/DefaultProvider;->onCreate(Landroid/content/Context;Landroid/content/Context;)V\n" +
        "\n" +
        "    :cond_c\n" +
        "\n" +
        "    const/4 v0, 0x1\n" +
        "\n" +
        "    return v0\n" +
        "\n" +
        "    :cond_e\n" +
        "\n" +
        "    const/4 v0, 0x0\n" +
        "\n" +
        "    return v0\n" +
        ".end method";

    public ApkProcessor(Context context) {
        this.context = context;
    }

    public interface ProgressListener {
        void onProgress(String status);
    }

    public static class ProcessingResult {
        public final boolean success;
        public final String message;
        public final File outputFile;
        public final Exception exception;

        public ProcessingResult(boolean success, String message, File outputFile, Exception exception) {
            this.success = success;
            this.message = message;
            this.outputFile = outputFile;
            this.exception = exception;
        }
    }

    public ProcessingResult processAndSignApk(Uri inputApkUri, File outputFile,
                                              List<ApkSigner.SignerConfig> signerConfigs,
                                              ProgressListener progressListener) {
        File tempDir = null;
        File tempUnsignedApk = null;

        try {
            tempDir = Files.createTempDirectory("apk_processing_").toFile();
            tempUnsignedApk = new File(tempDir, "unsigned_modified.apk");

            progressListener.onProgress("Copying input APK");
            File tempInputFile = copyUriToFile(inputApkUri, new File(tempDir, "input.apk"));

            progressListener.onProgress("Getting sorted DEX file list (highest to lowest)");
            List<String> sortedDexPaths = getSortedDexFilePaths(tempInputFile);

            File successfullyModifiedDexFile = null;
            String successfullyModifiedDexEntryPath = null;
            boolean overallModificationSuccess = false;

            for (String currentDexPath : sortedDexPaths) {
                progressListener.onProgress("Attempting to find method in: " + currentDexPath);

                // Create a dedicated temporary directory for this DEX attempt to isolate files
                File dexAttemptTempDir = new File(tempDir, "dex_attempt_" + new File(currentDexPath).getName().replace(".dex", ""));
                if (dexAttemptTempDir.exists()) { // Clean up from previous potential partial run
                    deleteDirectory(dexAttemptTempDir);
                }
                if (!dexAttemptTempDir.mkdirs()) {
                    Log.w(TAG, "Failed to create temp dir for DEX attempt: " + dexAttemptTempDir.getAbsolutePath() + ". Skipping this DEX.");
                    progressListener.onProgress("Skipping " + currentDexPath + " (failed to create temp dir)");
                    continue;
                }

                File extractedDexForThisAttempt = extractFileFromApk(tempInputFile, currentDexPath, dexAttemptTempDir);
                // extractedDexForThisAttempt is now, e.g., tempDir/dex_attempt_classes/classes.dex

                progressListener.onProgress("Modifying " + currentDexPath);
                boolean currentDexModificationSuccess = modifyMethodInDex(
                    extractedDexForThisAttempt, // This file will be modified in-place if successful
                    TARGET_CLASS_PATH,
                    TARGET_METHOD_SIGNATURE,
                    REPLACEMENT_METHOD_SMALI,
                    progressListener
                );

                if (currentDexModificationSuccess) {
                    progressListener.onProgress("Method found and modified in: " + currentDexPath);
                    successfullyModifiedDexFile = extractedDexForThisAttempt;
                    successfullyModifiedDexEntryPath = currentDexPath;
                    overallModificationSuccess = true;
                    // Do NOT delete dexAttemptTempDir here; its content (successfullyModifiedDexFile) is needed.
                    // It will be cleaned by the top-level tempDir cleanup in the finally block.
                    break; // Found and modified, stop searching
                } else {
                    progressListener.onProgress("Method not found or modification failed in: " + currentDexPath + ". Trying next DEX if available.");
                    // Clean up the temporary directory for this failed attempt as it's no longer needed
                    deleteDirectory(dexAttemptTempDir);
                }
            }

            if (!overallModificationSuccess) {
                return new ProcessingResult(false, "Target method not found in any DEX file, or modification failed.", null, null);
            }

            progressListener.onProgress("Updating APK with modified " + successfullyModifiedDexEntryPath);
            updateApkWithFile(tempInputFile, successfullyModifiedDexFile, successfullyModifiedDexEntryPath, tempUnsignedApk);

            progressListener.onProgress("Signing modified APK");
            signApk(tempUnsignedApk, outputFile, signerConfigs);

            progressListener.onProgress("APK processing complete: " + outputFile.getName());
            return new ProcessingResult(true, "Successfully modified and signed APK", outputFile, null);

        } catch (Exception e) {
            Log.e(TAG, "Error processing APK", e);
            progressListener.onProgress("Error: " + e.getMessage());
            return new ProcessingResult(false, "Processing failed: " + e.getMessage(), null, e);
        } finally {
            if (tempDir != null && tempDir.exists()) {
                deleteDirectory(tempDir);
            }
        }
    }

    /**
     * Gets all DEX file paths from the APK, sorted by number in descending order
     * (e.g., classes4.dex, classes3.dex, classes2.dex, classes.dex).
     */
    private List<String> getSortedDexFilePaths(File apkFile) throws IOException {
        try (ZipFile zipFile = new ZipFile(apkFile)) {
            List<String> dexFiles = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zipFile.entries();

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.matches("classes\\d*\\.dex")) { // Matches classes.dex, classes2.dex etc.
                    dexFiles.add(name);
                }
            }

            if (dexFiles.isEmpty()) {
                throw new IOException("No DEX files found in APK");
            }

            // Sort DEX files by number in descending order (highest to lowest)
            Collections.sort(dexFiles, new Comparator<String>() {
                @Override
                public int compare(String file1, String file2) {
                    Pattern pattern = Pattern.compile("classes(\\d*)\\.dex");
                    Matcher m1 = pattern.matcher(file1);
                    Matcher m2 = pattern.matcher(file2);

                    int num1 = 0; // classes.dex (no number) will be treated as 0 or 1
                    int num2 = 0;

                    if (m1.matches()) {
                        String numStr = m1.group(1);
                        if (numStr.isEmpty()) { // classes.dex
                            num1 = 1; // Typically classes.dex is effectively classes1.dex
                        } else {
                            num1 = Integer.parseInt(numStr);
                        }
                    }
                    if (m2.matches()) {
                        String numStr = m2.group(1);
                        if (numStr.isEmpty()) { // classes.dex
                            num2 = 1;
                        } else {
                            num2 = Integer.parseInt(numStr);
                        }
                    }
                    return Integer.compare(num2, num1); // Descending order (reversed parameters)
                }
            });

            Log.d(TAG, "Sorted DEX files (highest to lowest): " + dexFiles);
            return dexFiles;
        }
    }

    private File extractFileFromApk(File apkFile, String entryPath, File destDir) throws IOException {
        File extractedFile = new File(destDir, new File(entryPath).getName());

        try (ZipFile zipFile = new ZipFile(apkFile);
             InputStream is = zipFile.getInputStream(zipFile.getEntry(entryPath));
             FileOutputStream fos = new FileOutputStream(extractedFile)) {

            if (is == null) { // Should be redundant if getEntry() above worked, but good check
                throw new IOException("Entry not found in APK: " + entryPath);
            }

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
        }
        return extractedFile;
    }

    private boolean modifyMethodInDex(File dexFile, String targetClassPath, String targetMethodSignature,
                                    String replacementSmali, ProgressListener progressListener) throws IOException {
        // Smali output directory will be created inside dexFile's parent directory (which is a dex_attempt_... dir)
        File smaliDir = new File(dexFile.getParentFile(), "smali_output");
        if (smaliDir.exists()) { // Clean up if it exists from a failed prior step (shouldn't happen with dexAttemptTempDir logic)
            deleteDirectory(smaliDir);
        }
        if (!smaliDir.mkdirs()) {
            progressListener.onProgress("Failed to create smali output directory: " + smaliDir.getAbsolutePath());
            Log.e(TAG, "Failed to create smali output directory: " + smaliDir.getAbsolutePath());
            return false;
        }

        progressListener.onProgress("Disassembling " + dexFile.getName() + " to Smali in " + smaliDir.getName());
        BaksmaliOptions options = new BaksmaliOptions();
        options.deodex = false;
        // Consider options.apiLevel if you know the target API, otherwise default is used.

        try {
            DexBackedDexFile dexBackedDexFile = DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault());
            // Using Runtime.getRuntime().availableProcessors() for job count can be more adaptive
            Baksmali.disassembleDexFile(dexBackedDexFile, smaliDir, Runtime.getRuntime().availableProcessors(), options);
        } catch (Exception e) {
            Log.e(TAG, "Error disassembling DEX " + dexFile.getName(), e);
            progressListener.onProgress("Error disassembling " + dexFile.getName() + ": " + e.getMessage());
            return false;
        }

        String classSmaliFilePath = targetClassPath.replace('.', '/') + ".smali";
        File classSmaliFile = new File(smaliDir, classSmaliFilePath);

        if (!classSmaliFile.exists()) {
            // This is expected if the class isn't in this particular DEX file.
            Log.i(TAG, "Target class " + targetClassPath + " not found in " + dexFile.getName());
            progressListener.onProgress("Target class not found in " + dexFile.getName());
            return false;
        }
        progressListener.onProgress("Found target class file: " + classSmaliFile.getName());

        boolean methodReplaced = replaceMethodInSmaliFile(classSmaliFile, targetMethodSignature, replacementSmali);
        if (!methodReplaced) {
            // This is expected if the method isn't in this class (or class found in this DEX).
            Log.i(TAG, "Target method '" + targetMethodSignature + "' not found in " + classSmaliFile.getAbsolutePath());
            progressListener.onProgress("Target method not found in class " + classSmaliFile.getName());
            return false;
        }
        progressListener.onProgress("Method replaced successfully in " + classSmaliFile.getName());

        File modifiedDexFile = new File(dexFile.getParentFile(), "modified_" + dexFile.getName());
        progressListener.onProgress("Reassembling Smali to " + modifiedDexFile.getName());
        try {
            reassembleSmaliToDex(smaliDir, modifiedDexFile);
        } catch (Exception e) {
            Log.e(TAG, "Error reassembling Smali for " + dexFile.getName(), e);
            progressListener.onProgress("Error reassembling Smali: " + e.getMessage());
            return false; // Reassembly failed
        }


        // Replace the original extracted DEX with the modified one
        if (!dexFile.delete()) {
            Log.w(TAG, "Could not delete original extracted DEX file: " + dexFile.getAbsolutePath());
            // Try to proceed, rename might still work if OS handles it.
        }
        if (!modifiedDexFile.renameTo(dexFile)) {
            // If rename fails, try copying and then deleting source
            try {
                Files.copy(modifiedDexFile.toPath(), dexFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                if (!modifiedDexFile.delete()) {
                    Log.w(TAG, "Could not delete temporary modified DEX file after copy: " + modifiedDexFile.getAbsolutePath());
                }
            } catch (IOException ioe) {
                Log.e(TAG, "Failed to replace original DEX with modified version using copy: " + dexFile.getName(), ioe);
                throw new IOException("Failed to replace original DEX with modified version: " + dexFile.getName(), ioe);
            }
        }
        progressListener.onProgress(dexFile.getName() + " successfully modified.");
        return true;
    }

    /**
     * Replaces a method in a Smali file.
     *
     * @param smaliFile              The Smali file to modify.
     * @param methodSignatureToFind The exact starting line of the method signature (e.g., ".method public final foo()V").
     * @param fullReplacementMethodSmali The complete Smali code for the new method, including its own .method and .end method lines.
     * @return true if the method was found and replaced, false otherwise.
     * @throws IOException If an I/O error occurs.
     */
    private boolean replaceMethodInSmaliFile(File smaliFile, String methodSignatureToFind,
                                             String fullReplacementMethodSmali) throws IOException {
        List<String> originalLines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(smaliFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                originalLines.add(line);
            }
        }

        int methodStartIndex = -1;
        int methodEndIndex = -1;

        for (int i = 0; i < originalLines.size(); i++) {
            String currentLineTrimmed = originalLines.get(i).trim();
            if (methodStartIndex == -1 && currentLineTrimmed.equals(methodSignatureToFind)) {
                methodStartIndex = i;
            } else if (methodStartIndex != -1 && currentLineTrimmed.equals(".end method")) {
                methodEndIndex = i;
                break; // Found the .end method for the started method
            }
        }

        if (methodStartIndex == -1 || methodEndIndex == -1) {
            Log.w(TAG, "Target method block not fully found in " + smaliFile.getName() +
                       ". Signature: '" + methodSignatureToFind +
                       "'. Start index: " + methodStartIndex + ", End index: " + methodEndIndex);
            return false;
        }

        // Determine the indentation of the original method's signature line
        String indentation = "";
        String signatureLine = originalLines.get(methodStartIndex);
        for (char c : signatureLine.toCharArray()) {
            if (Character.isWhitespace(c) && c != '\n' && c != '\r') { // only leading whitespace
                indentation += c;
            } else {
                break;
            }
        }

        List<String> newFileLines = new ArrayList<>();
        // Add lines before the target method
        newFileLines.addAll(originalLines.subList(0, methodStartIndex));

        // Add the replacement method, re-indented line by line
        String[] replacementSmaliLines = fullReplacementMethodSmali.split("\n");
        for (String line : replacementSmaliLines) {
            String trimmedLine = line.trim();
            if (!trimmedLine.isEmpty()) {
                newFileLines.add(indentation + trimmedLine);
            } else {
                 // Preserve empty lines from the replacement Smali as they might be syntactically important (rarely for actual empty lines)
                newFileLines.add(""); // Add an empty line, not indented. Or 'indentation' if truly empty lines should be indented.
                                     // Smali usually doesn't care about indent of fully empty lines.
            }
        }

        // Add lines after the target method
        newFileLines.addAll(originalLines.subList(methodEndIndex + 1, originalLines.size()));

        // Write the modified content back to the file
        try (FileWriter writer = new FileWriter(smaliFile)) {
            for (int i = 0; i < newFileLines.size(); i++) {
                writer.write(newFileLines.get(i));
                if (i < newFileLines.size() - 1 || !newFileLines.get(i).isEmpty()) { // Avoid double newline at EOF if last line was empty
                    writer.write(System.lineSeparator());
                }
            }
        }
        Log.i(TAG, "Method '" + methodSignatureToFind + "' replaced in " + smaliFile.getAbsolutePath());
        return true;
    }


    private void reassembleSmaliToDex(File smaliDir, File outputDexFile) throws IOException {
        SmaliOptions options = new SmaliOptions();
        options.outputDexFile = outputDexFile.getAbsolutePath();
        // Consider options.apiLevel if needed
        Smali.assemble(options, smaliDir.getAbsolutePath());
    }

    private void updateApkWithFile(File originalApk, File fileToAddOrReplace, String entryPathInApk, File outputApk)
            throws IOException {
        try (ZipFile zipFile = new ZipFile(originalApk);
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outputApk))) {

            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            byte[] buffer = new byte[8192];

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                if (name.equals(entryPathInApk)) {
                    continue; // Skip the old version of the file we're replacing
                }

                // Skip signature-related files from META-INF, they'll be regenerated
                if (name.startsWith("META-INF/") &&
                    (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") ||
                     name.equalsIgnoreCase("MANIFEST.MF") || // some tools use upper case
                     name.endsWith(".EC") || name.startsWith("META-INF/SIG-"))) { // More signature types
                    continue;
                }

                ZipEntry newEntry = new ZipEntry(name);
                // Preserve original entry's method (e.g. DEFLATED or STORED)
                // and other relevant fields if necessary, though often not critical for APKs.
                // For simplicity, we just copy content, ZipOutputStream usually defaults to DEFLATED.
                // If preserving compression method is critical: newEntry.setMethod(entry.getMethod());
                // if (entry.getMethod() == ZipEntry.STORED) { newEntry.setCompressedSize(entry.getSize()); newEntry.setSize(entry.getSize()); newEntry.setCrc(entry.getCrc()); }

                zos.putNextEntry(newEntry);
                try (InputStream is = zipFile.getInputStream(entry)) {
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        zos.write(buffer, 0, bytesRead);
                    }
                }
                zos.closeEntry();
            }

            // Add the new/modified file
            Log.d(TAG, "Adding/replacing " + entryPathInApk + " with " + fileToAddOrReplace.getName() + " (" + fileToAddOrReplace.length() + " bytes)");
            ZipEntry newFileEntry = new ZipEntry(entryPathInApk);
            // If original entry was STORED, this new one should also be.
            // For .dex files, they are often STORED. Check original entry if available.
            // For simplicity, allow ZipOutputStream to decide or default to DEFLATED.
            zos.putNextEntry(newFileEntry);
            try (FileInputStream fis = new FileInputStream(fileToAddOrReplace)) {
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    zos.write(buffer, 0, bytesRead);
                }
            }
            zos.closeEntry();
        }
    }

    private File copyUriToFile(Uri uri, File destinationFile) throws IOException {
        try (InputStream is = context.getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(destinationFile)) {
            if (is == null) {
                throw new IOException("Failed to open input stream for URI: " + uri);
            }
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
        }
        return destinationFile;
    }

    private void signApk(File inputApk, File outputApk, List<ApkSigner.SignerConfig> signerConfigs)
            throws Exception {
        ApkSigner.Builder apkSignerBuilder = new ApkSigner.Builder(signerConfigs)
                .setInputApk(inputApk)
                .setOutputApk(outputApk)
                // Consider making minSdkVersion configurable or deriving it from the APK manifest
                .setMinSdkVersion(21); // Example: Android 5.0. Adjust as needed.

        apkSignerBuilder.build().sign();
    }

    private void deleteDirectory(File directory) {
        if (directory == null || !directory.exists()) {
            return;
        }
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    if (!file.delete()) {
                        Log.w(TAG, "Failed to delete file: " + file.getAbsolutePath());
                    }
                }
            }
        }
        if (!directory.delete()) {
            Log.w(TAG, "Failed to delete directory: " + directory.getAbsolutePath());
        }
    }
}
