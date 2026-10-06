package com.winlator;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.GraphicsDrivers;
import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;
import com.winlator.core.LocaleHelper;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.InputControlsManager;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.RootFSInstaller;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;

/** Single-purpose launcher: Steam bootstrap -> Counter-Strike 2 (AppID 730). */
public class CS2LauncherActivity extends AppCompatActivity {
    private static final String CS2_PROFILE_NAME = "CS2 Mobile";
    private static final String PREF_PROFILE_ID = "cs2_controls_profile_id";
    private static final String PREF_OVERLAY_OPACITY = "overlay_opacity";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView title;
    private TextView status;
    private Button action;
    private Button controlsButton;
    private Container container;
    private boolean creatingContainer;
    private boolean controlsSettingsVisible;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        AppUtils.hideSystemUI(this);
        AppUtils.keepScreenOn(this);

        buildUi();

        try {
            RootFSInstaller.installIfNeeded(this);
        } catch (Throwable t) {
            setState("Ошибка подготовки окружения: " + t.getClass().getSimpleName(), "ПОВТОРИТЬ", true, this::retryBootstrap);
            return;
        }

        handler.postDelayed(this::refreshState, 400);
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(newBase));
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppUtils.hideSystemUI(this);
        if (!controlsSettingsVisible && status != null) handler.postDelayed(this::refreshState, 500);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (controlsSettingsVisible) {
            controlsSettingsVisible = false;
            buildUi();
            handler.post(this::refreshState);
            return;
        }
        super.onBackPressed();
    }

    private void retryBootstrap() {
        try {
            RootFSInstaller.installIfNeeded(this);
            handler.postDelayed(this::refreshState, 700);
        } catch (Throwable t) {
            setState("Ошибка подготовки окружения: " + t.getClass().getSimpleName(), "ПОВТОРИТЬ", true, this::retryBootstrap);
        }
    }

    private void buildUi() {
        controlsSettingsVisible = false;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(28), dp(28), dp(28));
        root.setBackgroundColor(Color.rgb(12, 14, 18));

        title = new TextView(this);
        title.setText("CS2 MOBILE");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(16));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setTextColor(Color.rgb(190, 196, 205));
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 0, 0, dp(24));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        action = new Button(this);
        action.setAllCaps(false);
        action.setTextSize(17);
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(dp(280), dp(58));
        root.addView(action, actionLp);

        controlsButton = new Button(this);
        controlsButton.setAllCaps(false);
        controlsButton.setText("УПРАВЛЕНИЕ");
        controlsButton.setTextSize(15);
        controlsButton.setOnClickListener(v -> showControlsSettings());
        LinearLayout.LayoutParams controlsLp = new LinearLayout.LayoutParams(dp(280), dp(54));
        controlsLp.topMargin = dp(12);
        root.addView(controlsButton, controlsLp);

        TextView note = new TextView(this);
        note.setText("Steam-вход выполняет сам Steam. CS2 скачивается из Steam и не входит в APK.");
        note.setTextColor(Color.rgb(115, 123, 136));
        note.setTextSize(12);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(22), 0, 0);
        root.addView(note, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
    }

    private void showControlsSettings() {
        controlsSettingsVisible = true;
        ControlsProfile profile = ensureCs2ControlsProfile();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(28), dp(24), dp(28), dp(24));
        root.setBackgroundColor(Color.rgb(12, 14, 18));

        TextView header = new TextView(this);
        header.setText("УПРАВЛЕНИЕ");
        header.setTextColor(Color.WHITE);
        header.setTextSize(25);
        header.setGravity(Gravity.CENTER);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = new TextView(this);
        hint.setText("Перетаскивай кнопки, меняй размер, прозрачность и назначение.");
        hint.setTextColor(Color.rgb(170, 177, 188));
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(8), 0, dp(22));
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        TextView sensitivityLabel = new TextView(this);
        sensitivityLabel.setTextColor(Color.WHITE);
        sensitivityLabel.setTextSize(15);
        root.addView(sensitivityLabel, new LinearLayout.LayoutParams(-1, -2));

        SeekBar sensitivity = new SeekBar(this);
        sensitivity.setMax(100);
        float currentSpeed = profile != null && !Float.isNaN(profile.getCursorSpeed()) ? profile.getCursorSpeed() : 1.0f;
        sensitivity.setProgress(speedToProgress(currentSpeed));
        updateSensitivityLabel(sensitivityLabel, currentSpeed);
        sensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float speed = progressToSpeed(progress);
                updateSensitivityLabel(sensitivityLabel, speed);
                if (fromUser && profile != null) {
                    profile.setCursorSpeed(speed);
                    profile.save();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(sensitivity, new LinearLayout.LayoutParams(-1, -2));

        TextView opacityLabel = new TextView(this);
        opacityLabel.setTextColor(Color.WHITE);
        opacityLabel.setTextSize(15);
        opacityLabel.setPadding(0, dp(16), 0, 0);
        root.addView(opacityLabel, new LinearLayout.LayoutParams(-1, -2));

        SeekBar opacity = new SeekBar(this);
        opacity.setMax(100);
        int opacityProgress = Math.round(prefs.getFloat(PREF_OVERLAY_OPACITY, 0.65f) * 100f);
        opacity.setProgress(opacityProgress);
        updateOpacityLabel(opacityLabel, opacityProgress);
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int clamped = Math.max(15, progress);
                updateOpacityLabel(opacityLabel, clamped);
                if (fromUser) prefs.edit().putFloat(PREF_OVERLAY_OPACITY, clamped / 100f).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(opacity, new LinearLayout.LayoutParams(-1, -2));

        Button editor = new Button(this);
        editor.setAllCaps(false);
        editor.setText("РЕДАКТОР HUD");
        editor.setTextSize(16);
        editor.setEnabled(profile != null);
        editor.setOnClickListener(v -> {
            ControlsProfile latest = ensureCs2ControlsProfile();
            if (latest == null) return;
            Intent intent = new Intent(this, ControlsEditorActivity.class);
            intent.putExtra("profile_id", latest.id);
            startActivity(intent);
        });
        LinearLayout.LayoutParams editorLp = new LinearLayout.LayoutParams(dp(300), dp(56));
        editorLp.topMargin = dp(22);
        root.addView(editor, editorLp);

        Button reset = new Button(this);
        reset.setAllCaps(false);
        reset.setText("СБРОСИТЬ РАСКЛАДКУ");
        reset.setOnClickListener(v -> {
            resetCs2ControlsProfile();
            prefs.edit().putFloat(PREF_OVERLAY_OPACITY, 0.65f).apply();
            showControlsSettings();
        });
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(dp(300), dp(50));
        resetLp.topMargin = dp(14);
        root.addView(reset, resetLp);

        Button back = new Button(this);
        back.setAllCaps(false);
        back.setText("НАЗАД");
        back.setOnClickListener(v -> {
            controlsSettingsVisible = false;
            buildUi();
            refreshState();
        });
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(dp(300), dp(50));
        backLp.topMargin = dp(10);
        root.addView(back, backLp);

        setContentView(root);
    }

    private void updateSensitivityLabel(TextView label, float speed) {
        label.setText(String.format(java.util.Locale.US, "Чувствительность камеры: %.2fx", speed));
    }

    private void updateOpacityLabel(TextView label, int value) {
        label.setText("Прозрачность HUD: " + value + "%");
    }

    private int speedToProgress(float speed) {
        float clamped = Math.max(0.25f, Math.min(3.0f, speed));
        return Math.round(((clamped - 0.25f) / 2.75f) * 100f);
    }

    private float progressToSpeed(int progress) {
        return 0.25f + (Math.max(0, Math.min(100, progress)) / 100f) * 2.75f;
    }

    private ControlsProfile ensureCs2ControlsProfile() {
        InputControlsManager manager = new InputControlsManager(this);
        ArrayList<ControlsProfile> profiles = manager.getProfiles();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int savedId = prefs.getInt(PREF_PROFILE_ID, 0);
        if (savedId > 0) {
            ControlsProfile saved = manager.getProfile(savedId);
            if (saved != null && CS2_PROFILE_NAME.equals(saved.getName())) return saved;
        }

        for (ControlsProfile p : profiles) {
            if (CS2_PROFILE_NAME.equals(p.getName())) {
                prefs.edit().putInt(PREF_PROFILE_ID, p.id).apply();
                return p;
            }
        }

        ControlsProfile fps = manager.getProfile(4);
        if (fps == null) return null;

        ControlsProfile custom = manager.duplicateProfile(fps);
        custom.setName(CS2_PROFILE_NAME);
        custom.save();
        prefs.edit().putInt(PREF_PROFILE_ID, custom.id).apply();
        return custom;
    }

    private void resetCs2ControlsProfile() {
        ControlsProfile profile = ensureCs2ControlsProfile();
        if (profile == null) return;

        try {
            JSONObject data = new JSONObject(FileUtils.readString(this, "inputcontrols/profiles/controls-4.icp"));
            data.put("id", profile.id);
            data.put("name", CS2_PROFILE_NAME);
            FileUtils.writeString(ControlsProfile.getProfileFile(this, profile.id), data.toString());
        } catch (Exception ignored) {}
    }

    private void refreshState() {
        if (controlsSettingsVisible) return;

        RootFS rootFS = RootFS.find(this);
        if (!rootFS.isValid()) {
            setState("Подготовка игрового окружения…", "ПОДОЖДАТЬ", false, null);
            handler.postDelayed(this::refreshState, 1200);
            return;
        }

        ensureCs2ControlsProfile();

        if (container == null) {
            ContainerManager manager = new ContainerManager(this);
            ArrayList<Container> containers = manager.getContainers();
            if (!containers.isEmpty()) {
                container = containers.get(0);
            } else if (!creatingContainer) {
                creatingContainer = true;
                setState("Создание окружения CS2…", "ПОДОЖДАТЬ", false, null);
                createCs2Container(manager);
                return;
            } else {
                handler.postDelayed(this::refreshState, 800);
                return;
            }
        }

        ensureLauncherBats();
        File steam = steamExe();
        if (!steam.exists()) {
            setState("Первый запуск: установим Steam внутри приложения", "УСТАНОВИТЬ STEAM", true,
                    () -> launchBat("install_steam.bat", "", false));
            return;
        }

        if (!cs2Manifest().exists()) {
            setState("Войди в Steam и установи Counter-Strike 2 (AppID 730)", "ОТКРЫТЬ STEAM", true,
                    () -> launch(steam, "", false));
            return;
        }

        setState("CS2 установлен · управление можно настроить под себя", "ИГРАТЬ", true,
                () -> launch(steam, "-silent -applaunch 730 -novid -fullscreen +fps_max 30", true));
    }

    private void createCs2Container(ContainerManager manager) {
        try {
            JSONObject data = new JSONObject();
            data.put("name", "CS2 Mobile");
            data.put("screenSize", "960x540");
            data.put("envVars", Container.DEFAULT_ENV_VARS + " DXVK_LOG_LEVEL=none");
            data.put("graphicsDriver", GraphicsDrivers.VORTEK + "," + GraphicsDrivers.GLADIO);
            data.put("dxwrapper", Container.DEFAULT_DXWRAPPER);
            data.put("dxwrapperConfig", "");
            data.put("graphicsDriverConfig", "");
            data.put("audioDriver", Container.DEFAULT_AUDIO_DRIVER);
            data.put("audioDriverConfig", "");
            data.put("wincomponents", Container.DEFAULT_WINCOMPONENTS);
            data.put("drives", Container.DEFAULT_DRIVES);
            data.put("hudMode", 0);
            data.put("startupSelection", Container.STARTUP_SELECTION_ESSENTIAL);
            data.put("box64Preset", "PERFORMANCE");
            data.put("desktopTheme", "LIGHT,IMAGE,#0277bd");
            manager.createContainerAsync(data, created -> {
                container = created;
                creatingContainer = false;
                runOnUiThread(this::refreshState);
            });
        } catch (Exception e) {
            creatingContainer = false;
            setState("Не удалось создать окружение: " + e.getMessage(), "ПОВТОРИТЬ", true, this::refreshState);
        }
    }

    private void ensureLauncherBats() {
        File c = new File(container.getRootDir(), ".wine/drive_c");
        File install = new File(c, "install_steam.bat");
        if (!install.exists()) {
            write(install, "@echo off\r\nZ:\\opt\\apps\\winaddons.exe -n \"Steam (Legacy)\" -d \"Steam\" -e \"steam.exe\"\r\n");
        }
    }

    private void launchBat(String name, String args, boolean controls) {
        File bat = new File(container.getRootDir(), ".wine/drive_c/" + name);
        launch(bat, args, controls);
    }

    private void launch(File exe, String args, boolean controls) {
        Intent i = new Intent(this, XServerDisplayActivity.class);
        i.putExtra("container_id", container.id);
        i.putExtra("exec_path", exe.getAbsolutePath());
        if (args != null && !args.isEmpty()) i.putExtra("exec_args", args);
        if (controls) {
            ControlsProfile profile = ensureCs2ControlsProfile();
            if (profile != null) i.putExtra("controls_profile_id", profile.id);
        }
        startActivity(i);
    }

    private File steamExe() {
        File p86 = new File(container.getRootDir(), ".wine/drive_c/Program Files (x86)/Steam/steam.exe");
        if (p86.exists()) return p86;
        return new File(container.getRootDir(), ".wine/drive_c/Program Files/Steam/steam.exe");
    }

    private File cs2Manifest() {
        File steam = steamExe().getParentFile();
        return new File(steam, "steamapps/appmanifest_730.acf");
    }

    private void setState(String text, String buttonText, boolean enabled, Runnable callback) {
        runOnUiThread(() -> {
            if (status == null || action == null) return;
            status.setText(text);
            action.setText(buttonText);
            action.setEnabled(enabled);
            action.setVisibility(View.VISIBLE);
            action.setOnClickListener(v -> { if (callback != null) callback.run(); });
        });
    }

    private static void write(File file, String data) {
        try {
            file.getParentFile().mkdirs();
            java.nio.file.Files.write(file.toPath(), data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
