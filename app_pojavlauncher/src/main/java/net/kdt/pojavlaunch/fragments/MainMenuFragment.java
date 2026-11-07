package net.kdt.pojavlaunch.fragments;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.mcVersionSpinner;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.multirt.MultiRTConfigDialog;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainMenuFragment extends Fragment {
    public static final String TAG = "MainMenuFragment";
    private static final String URL_VERSION_TXT = "https://raw.githubusercontent.com/joaombsantos/modpack_colisao_cobblemon/main/version.txt";
    private static final String URL_MODPACK_ZIP = "https://github.com/joaombsantos/modpack_colisao_cobblemon/releases/download/v1.4.1/modpack.zip";

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

        new Thread(() -> {
            try {
                File gameDir = new File(Tools.DIR_GAME_NEW);

                if (!gameDir.exists()) {
                    gameDir.mkdirs();
                }

                File localVersionFile = new File(gameDir, "modpack_version.txt");

                String remoteVersion = fetchTextFromUrl(URL_VERSION_TXT).trim();

                String localVersion = "";
                if (localVersionFile.exists()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(localVersionFile)));
                    localVersion = reader.readLine();
                    if (localVersion != null) localVersion = localVersion.trim();
                    reader.close();
                }

                if (!remoteVersion.equals(localVersion) && !remoteVersion.isEmpty()) {
                    requireActivity().runOnUiThread(() -> playButton.setText("BAIXANDO MODPACK..."));

                    File zipFile = new File(requireContext().getCacheDir(), "modpack_temp.zip");
                    downloadFile(URL_MODPACK_ZIP, zipFile);

                    requireActivity().runOnUiThread(() -> playButton.setText("EXTRAINDO ARQUIVOS..."));
                    unzip(zipFile, gameDir);

                    FileOutputStream fos = new FileOutputStream(localVersionFile);
                    fos.write(remoteVersion.getBytes());
                    fos.close();

                    zipFile.delete();
                }

//                requireActivity().runOnUiThread(() -> {
//                    playButton.setText(R.string.main_play);
//                    playButton.setEnabled(true);
//                    launchGame();
//                });
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

    private void downloadFile(String urlString, File destFile) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.connect();

        int status = connection.getResponseCode();
        if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                status == HttpURLConnection.HTTP_MOVED_PERM ||
                status == HttpURLConnection.HTTP_SEE_OTHER) {

            String newUrl = connection.getHeaderField("Location");
            connection = (HttpURLConnection) new URL(newUrl).openConnection();
            connection.connect();
        }

        if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new Exception("Erro HTTP: " + connection.getResponseCode());
        }

        InputStream input = new BufferedInputStream(connection.getInputStream(), 8192);
        FileOutputStream output = new FileOutputStream(destFile);

        byte[] data = new byte[1024];
        int count;
        while ((count = input.read(data)) != -1) {
            output.write(data, 0, count);
        }

        output.flush();
        output.close();
        input.close();
        connection.disconnect();
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
        File runtimesDir = new File(Tools.DIR_DATA, "runtimes");
        boolean hasJava21 = false;

        if (runtimesDir.exists() && runtimesDir.listFiles() != null) {
            for (File file : runtimesDir.listFiles()) {
                if (file.isDirectory() && (file.getName().contains("21") || file.getName().contains("jre21") || file.getName().contains("internal-java-21"))) {
                    hasJava21 = true;
                    break;
                }
            }
        }

        if (!hasJava21) {
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