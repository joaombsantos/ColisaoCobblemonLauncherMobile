package net.kdt.pojavlaunch.fragments;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.mcVersionSpinner;

import net.kdt.pojavlaunch.Architecture;
import net.kdt.pojavlaunch.NewJREUtil;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.multirt.MultiRTConfigDialog;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainMenuFragment extends Fragment {
    public static final String TAG = "MainMenuFragment";
    private static final String URL_VERSION_TXT = "https://raw.githubusercontent.com/joaombsantos/modpack_colisao_cobblemon/main/version.txt";
    private static final String URL_MODPACK_ZIP = "https://github.com/joaombsantos/modpack_colisao_cobblemon/releases/download/v1.4.1/modpack.zip";

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final long DOWNLOAD_TOTAL_TIMEOUT_MS = 30 * 60 * 1000L;
    private static final int DOWNLOAD_MAX_ATTEMPTS = 4;
    private static final long RETRY_DELAY_MS = 2_000L;
    private static final int MAX_REDIRECTS = 5;

    private mcVersionSpinner mVersionSpinner;

    public MainMenuFragment(){
        super(R.layout.fragment_launcher);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Button mSiteButton = view.findViewById(R.id.site_button);
        Button mDiscordButton = view.findViewById(R.id.discord_button);
        Button mSocialMediaButton = view.findViewById(R.id.social_media);
        Button mPlayButton = view.findViewById(R.id.play_button);

        mVersionSpinner = view.findViewById(R.id.mc_version_spinner);

        mSiteButton.setOnClickListener(v -> Tools.openURL(requireActivity(), Tools.URL_HOME));
        mDiscordButton.setOnClickListener(v -> Tools.openURL(requireActivity(), getString(R.string.discord_invite)));
        mSocialMediaButton.setOnClickListener(v -> Tools.openURL(requireActivity(), "https://linktr.ee/colisao"));

        mPlayButton.setOnClickListener(v -> {
            mPlayButton.setEnabled(false);

            checkAndUpdateModpack(mPlayButton);
        });

        mSiteButton.setOnLongClickListener((v)->{
            Tools.swapFragment(requireActivity(), GamepadMapperFragment.class, GamepadMapperFragment.TAG, null);
            return true;
        });
    }

    private void checkAndUpdateModpack(Button playButton) {
        playButton.setText("VERIFICANDO ATUALIZAÇÃO...");

        final Context appContext = requireContext().getApplicationContext();
        final DownloadStatus status = new DownloadStatus();

        new Thread(() -> {
            try {
                runInParallel(
                        () -> {
                            updateModpackIfNeeded(appContext, playButton, status);
                            return null;
                        },
                        () -> {
                            installJava21IfNeeded(appContext, playButton, status);
                            return null;
                        });

                checkJavaAndLaunch(playButton);

            } catch (Exception e) {
                Log.e(TAG, "Erro ao atualizar modpack", e);
                final String errorMessage = e.getMessage();

                requireActivity().runOnUiThread(() -> {
                    playButton.setText(R.string.main_play);
                    playButton.setEnabled(true);

                    Toast.makeText(requireContext(), "Falha crítica: " + errorMessage, Toast.LENGTH_LONG).show();

                });
            }
        }).start();
    }
    private static final class DownloadStatus {
        volatile String modpack;
        volatile String java;
    }

    private interface ProgressListener {
        void onProgress(int percent);
    }

    private void runOnUi(Runnable action) {
        Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(action);
        }
    }
    private void refreshButton(Button button, DownloadStatus status) {
        runOnUi(() -> {
            String modpack = status.modpack;
            String java = status.java;
            StringBuilder text = new StringBuilder();
            if (modpack != null) text.append(modpack);
            if (java != null) {
                if (text.length() > 0) text.append(" | ");
                text.append(java);
            }
            button.setText(text.length() > 0 ? text.toString() : "VERIFICANDO ATUALIZAÇÃO...");
        });
    }

    private static String percentLabel(String name, int percent) {
        return percent >= 0 ? name + " " + percent + "%" : name + " BAIXANDO...";
    }
    private static void runInParallel(Callable<Void> first, Callable<Void> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ExecutorCompletionService<Void> completion = new ExecutorCompletionService<>(pool);
        Future<Void> firstFuture = completion.submit(first);
        Future<Void> secondFuture = completion.submit(second);

        boolean finished = false;
        try {
            completion.take().get();
            completion.take().get();
            finished = true;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        } finally {
            if (!finished) {
                firstFuture.cancel(true);
                secondFuture.cancel(true);
            }
            pool.shutdownNow();
        }
    }
    private void updateModpackIfNeeded(Context context, Button button, DownloadStatus status) throws Exception {
        try {
            File gameDir = new File(Tools.DIR_GAME_NEW);

            if (!gameDir.exists()) {
                gameDir.mkdirs();
            }

            File localVersionFile = new File(gameDir, "modpack_version.txt");

            String remoteVersion = fetchTextFromUrl(URL_VERSION_TXT).trim();

            String localVersion = "";
            if (localVersionFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(localVersionFile)))) {
                    localVersion = reader.readLine();
                }
                if (localVersion != null) localVersion = localVersion.trim();
            }

            if (!remoteVersion.equals(localVersion) && !remoteVersion.isEmpty()) {
                File zipFile = new File(context.getCacheDir(), "modpack_temp.zip");
                try {
                    status.modpack = percentLabel("MODPACK", 0);
                    refreshButton(button, status);

                    downloadFile(URL_MODPACK_ZIP, zipFile, percent -> {
                        status.modpack = percentLabel("MODPACK", percent);
                        refreshButton(button, status);
                    });

                    status.modpack = "MODPACK EXTRAINDO...";
                    refreshButton(button, status);
                    unzip(zipFile, gameDir);

                    try (FileOutputStream fos = new FileOutputStream(localVersionFile)) {
                        fos.write(remoteVersion.getBytes());
                    }
                } finally {
                    zipFile.delete();
                }
            }

            status.modpack = null;
            refreshButton(button, status);
        } catch (Exception e) {
            throw new Exception("Falha ao atualizar o modpack: " + e.getMessage(), e);
        }
    }
    private void installJava21IfNeeded(Context context, Button button, DownloadStatus status) throws Exception {
        if (hasJava21()) {
            return;
        }

        final String jreName = "External-21";
        File archive = null;

        try {
            if (Architecture.getDeviceArchitecture() == Architecture.ARCH_X86) {
                throw new Exception("Java 21 não é suportado em dispositivos x86.");
            }
            if (!Tools.isOnline(context)) {
                throw new Exception("sem conexão com a internet.");
            }

            String arch = Architecture.archAsString(Architecture.getDeviceArchitecture());
            archive = new File(Tools.DIR_CACHE, "jre21-android-" + arch + ".tar.xz");

            status.java = percentLabel("JAVA 21", 0);
            refreshButton(button, status);

            downloadFile(NewJREUtil.ExternalRuntime.JRE_21.downloadLink, archive, percent -> {
                status.java = percentLabel("JAVA 21", percent);
                refreshButton(button, status);
            });

            status.java = "JAVA 21 INSTALANDO...";
            refreshButton(button, status);

            try (FileInputStream in = new FileInputStream(archive)) {
                MultiRTUtils.installRuntimeNamed(Tools.NATIVE_LIB_DIR, in, jreName);
            }
            MultiRTUtils.postPrepare(jreName);

            if (MultiRTUtils.forceReread(jreName).javaVersion != 21) {
                throw new Exception("o Java 21 foi extraído, mas não pôde ser lido (arquivo release inválido).");
            }

            status.java = null;
            refreshButton(button, status);
        } catch (Exception e) {
            try {
                MultiRTUtils.removeRuntimeNamed(jreName);
            } catch (Exception ignored) {
            }
            throw new Exception("Falha ao instalar o Java 21: " + e.getMessage(), e);
        } finally {
            if (archive != null) {
                archive.delete();
            }
        }
    }
    private boolean hasJava21() {
        for (Runtime runtime : MultiRTUtils.getInstalledRuntimes()) {
            if (MultiRTUtils.forceReread(runtime.name).javaVersion == 21) {
                return true;
            }
        }
        return false;
    }

    private void launchGame() {
        File libsDir = new File(Tools.DIR_GAME_NEW, "libraries");
        if (!libsDir.exists() || libsDir.list() == null || libsDir.list().length == 0) {
            requireActivity().runOnUiThread(() ->
                    Toast.makeText(requireContext(), "ERRO CRÍTICO: Pasta libraries não encontrada ou vazia! O jogo não pode iniciar.", Toast.LENGTH_LONG).show()
            );
            return;
        }

        copyFabricFromAssetsIfNeeded();
        String profileUuid = "11111111-2222-3333-4444-555555555555";
        String versionId = "fabric-loader-0.18.4-1.21.1";

        try {
            File gameDir = new File(Tools.DIR_GAME_NEW);
            if (!gameDir.exists()) {
                gameDir.mkdirs();
            }

            File profilesFile = new File(gameDir, "launcher_profiles.json");

            String jsonContent = "{\n" +
                    "  \"profiles\": {\n" +
                    "    \"" + profileUuid + "\": {\n" +
                    "      \"name\": \"Colisao Cobblemon\",\n" +
                    "      \"lastVersionId\": \"" + versionId + "\"\n" +
                    "    }\n" +
                    "  },\n" +
                    "  \"selectedProfile\": \"" + profileUuid + "\"\n" +
                    "}";

            FileOutputStream fos = new FileOutputStream(profilesFile);
            fos.write(jsonContent.getBytes());
            fos.flush();
            fos.close();

            net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles.load();

            String currentProfile = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");

            if (currentProfile.isEmpty() || !net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles.mainProfileJson.profiles.containsKey(currentProfile)) {
                LauncherPreferences.DEFAULT_PREF.edit()
                        .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, profileUuid)
                        .commit();
            }

            if (mVersionSpinner != null) {
                mVersionSpinner.reloadProfiles();
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao criar launcher_profiles.json", e);
        }

        ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
    }

    private String fetchTextFromUrl(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        String inputLine;
        StringBuilder content = new StringBuilder();
        while ((inputLine = in.readLine()) != null) {
            content.append(inputLine);
        }
        in.close();
        connection.disconnect();
        return content.toString();
    }
    private void downloadFile(String urlString, File destFile, ProgressListener listener) throws Exception {
        if (destFile.exists() && !destFile.delete()) {
            throw new Exception("Não foi possível limpar o arquivo temporário do download.");
        }

        final long deadline = SystemClock.elapsedRealtime() + DOWNLOAD_TOTAL_TIMEOUT_MS;
        IOException lastError = null;
        boolean success = false;

        try {
            for (int attempt = 1; attempt <= DOWNLOAD_MAX_ATTEMPTS; attempt++) {
                try {
                    downloadAttempt(urlString, destFile, deadline, listener);
                    success = true;
                    return;
                } catch (IOException e) {
                    lastError = e;
                    Log.w(TAG, "Download falhou (tentativa " + attempt + "/" + DOWNLOAD_MAX_ATTEMPTS + ")", e);
                }

                if (attempt < DOWNLOAD_MAX_ATTEMPTS) {
                    long wait = Math.min(RETRY_DELAY_MS * attempt, deadline - SystemClock.elapsedRealtime());
                    if (wait <= 0) break;
                    Thread.sleep(wait);
                }
            }

            String reason = lastError != null ? lastError.getMessage() : "tempo esgotado";
            throw new Exception("Falha no download: " + reason);
        } finally {
            if (!success) {
                destFile.delete();
            }
        }
    }
    private void downloadAttempt(String urlString, File destFile, long deadline, ProgressListener listener) throws Exception {
        long existingBytes = destFile.exists() ? destFile.length() : 0;
        HttpURLConnection connection = openWithRedirects(urlString, existingBytes);

        try {
            int status = connection.getResponseCode();
            boolean append;

            if (status == HttpURLConnection.HTTP_PARTIAL) {
                append = true;
            } else if (status == HttpURLConnection.HTTP_OK) {
                append = false;
                existingBytes = 0;
            } else if (status == 416) {
                destFile.delete();
                throw new IOException("Servidor recusou retomar o download (HTTP 416)");
            } else if (status >= 500 || status == 408 || status == 429) {
                throw new IOException("Erro HTTP temporário: " + status);
            } else {
                throw new Exception("Erro HTTP: " + status);
            }

            long remaining = connection.getContentLengthLong();
            long expectedTotal = remaining >= 0 ? existingBytes + remaining : -1;
            long written = existingBytes;
            int lastPercent = reportProgress(listener, written, expectedTotal, -2);

            try (InputStream input = new BufferedInputStream(connection.getInputStream(), 8192);
                 FileOutputStream output = new FileOutputStream(destFile, append)) {

                byte[] data = new byte[8192];
                int count;
                while ((count = input.read(data)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Download cancelado");
                    }
                    if (SystemClock.elapsedRealtime() > deadline) {
                        throw new Exception("Tempo limite do download excedido");
                    }
                    output.write(data, 0, count);
                    written += count;
                    lastPercent = reportProgress(listener, written, expectedTotal, lastPercent);
                }
                output.flush();
            }

            if (expectedTotal >= 0 && written != expectedTotal) {
                throw new IOException("Download incompleto: " + written + " de " + expectedTotal + " bytes");
            }
        } finally {
            connection.disconnect();
        }
    }
    private static int reportProgress(ProgressListener listener, long done, long total, int lastPercent) {
        if (listener == null) return lastPercent;
        int percent = total > 0 ? (int) Math.min(100L, done * 100L / total) : -1;
        if (percent != lastPercent) {
            listener.onProgress(percent);
        }
        return percent;
    }
    private HttpURLConnection openWithRedirects(String urlString, long resumeFrom) throws IOException {
        String current = urlString;

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (resumeFrom > 0) {
                connection.setRequestProperty("Range", "bytes=" + resumeFrom + "-");
            }

            try {
                int status = connection.getResponseCode();
                if (status == HttpURLConnection.HTTP_MOVED_PERM
                        || status == HttpURLConnection.HTTP_MOVED_TEMP
                        || status == HttpURLConnection.HTTP_SEE_OTHER
                        || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    if (location == null) {
                        throw new IOException("Redirecionamento sem destino (HTTP " + status + ")");
                    }
                    current = new URL(new URL(current), location).toString();
                    continue;
                }
                return connection;
            } catch (IOException e) {
                connection.disconnect();
                throw e;
            }
        }

        throw new IOException("Redirecionamentos demais");
    }

    private void unzip(File zipFile, File targetDirectory) throws Exception {
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)));
        ZipEntry ze;
        byte[] buffer = new byte[8192];

        while ((ze = zis.getNextEntry()) != null) {
            File file = new File(targetDirectory, ze.getName());
            File dir = ze.isDirectory() ? file : file.getParentFile();

            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new Exception("Falha ao criar diretório: " + dir.getAbsolutePath());
            }

            if (ze.isDirectory()) {
                continue;
            }

            FileOutputStream fout = new FileOutputStream(file);
            int count;
            while ((count = zis.read(buffer)) != -1) {
                fout.write(buffer, 0, count);
            }
            fout.close();
            zis.closeEntry();
        }
        zis.close();
    }
    private void copyFabricFromAssetsIfNeeded() {
        String fabricVersion = "fabric-loader-0.18.4-1.21.1";
        File versionsDir = new File(Tools.DIR_GAME_NEW, "versions/" + fabricVersion);

        if (!versionsDir.exists()) {
            versionsDir.mkdirs();
        }

        File targetJson = new File(versionsDir, fabricVersion + ".json");
        File targetJar = new File(versionsDir, fabricVersion + ".jar");

        try {
            if (!targetJson.exists()) {
                Log.i(TAG, "Copiando Fabric JSON dos assets...");
                copyAssetFile("versions/" + fabricVersion + "/" + fabricVersion + ".json", targetJson);
            }
            if (!targetJar.exists()) {
                Log.i(TAG, "Copiando Fabric JAR dos assets...");
                copyAssetFile("versions/" + fabricVersion + "/" + fabricVersion + ".jar", targetJar);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao extrair arquivos do Fabric dos assets", e);
        }

        String vanillaVersion = "1.21.1";
        File vanillaDir = new File(Tools.DIR_GAME_NEW, "versions/" + vanillaVersion);

        if (!vanillaDir.exists()) {
            vanillaDir.mkdirs();
        }

        File vanillaJson = new File(vanillaDir, vanillaVersion + ".json");

        try {
            if (!vanillaJson.exists()) {
                Log.i(TAG, "Copiando Vanilla JSON 1.21.1 dos assets...");
                copyAssetFile("versions/" + vanillaVersion + "/" + vanillaVersion + ".json", vanillaJson);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao extrair arquivos Vanilla 1.21.1 dos assets", e);
        }
    }

    private void copyAssetFile(String assetPath, File targetFile) throws Exception {
        InputStream in = requireActivity().getAssets().open(assetPath);
        FileOutputStream out = new FileOutputStream(targetFile);

        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }

        out.flush();
        out.close();
        in.close();
    }

    private void checkJavaAndLaunch(Button playButton) {
        boolean javaOk = hasJava21();

        if (!javaOk) {
            requireActivity().runOnUiThread(() -> {
                playButton.setText(R.string.main_play);
                playButton.setEnabled(true);
                Toast.makeText(requireContext(), "Java 21 não encontrado! Por favor, baixe-o na lista a seguir.", Toast.LENGTH_LONG).show();

                MultiRTConfigDialog dialogScreen = new MultiRTConfigDialog();
                dialogScreen.prepare(getContext(), null);
                dialogScreen.show();
            });
        } else {
            requireActivity().runOnUiThread(() -> {
                playButton.setText(R.string.main_play);
                playButton.setEnabled(true);
                launchGame();
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mVersionSpinner != null) {
            mVersionSpinner.reloadProfiles();
        }
    }
}