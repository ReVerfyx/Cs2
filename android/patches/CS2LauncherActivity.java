package com.winlator;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
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

/**
 * Minimal CS2-only Android launcher.
 * Launcher stays portrait. Steam/CS2 runtime remains landscape.
 */
public class CS2LauncherActivity extends AppCompatActivity {
    private static final int REQUEST_STARTUP_PERMISSIONS = 730;
    private static final String CS2_PROFILE_NAME = "CS2 Mobile";
    private static final String PREF_PROFILE_ID = "cs2_controls_profile_id";
    private static final String PREF_OVERLAY_OPACITY = "overlay_opacity";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private LinearLayout rootView;
    private TextView title;
    private TextView status;
    private Button action;
    private Button controlsButton;
    private Button settingsButton;

    private Container container;
    private boolean creatingContainer;
    private boolean subPageVisible;
    private boolean bootstrapStarted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        AppUtils.keepScreenOn(this);

        buildMainUi();
        animateIn();
        initializeState();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(newBase));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (rootView != null) {
            rootView.setAlpha(1f);
            rootView.setTranslationY(0f);
        }
        if (!subPageVisible && bootstrapStarted && status != null) {
            handler.postDelayed(this::refreshState, 450);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (subPageVisible) {
            subPageVisible = false;
            buildMainUi();
            animateIn();
            if (bootstrapStarted) refreshState();
            else initializeState();
            return;
        }
        super.onBackPressed();
    }

    private void initializeState() {
        RootFS rootFS = RootFS.find(this);

        if (rootFS.isValid()) {
            bootstrapStarted = true;
            refreshState();
            return;
        }

        if (hasMissingStartupPermissions()) {
            setState("Нужны разрешения Android", "Продолжить", true, this::requestStartupPermissions);
        } else {
            setState("Первичная настройка", "Начать", true, this::startBootstrap);
        }
    }

    private boolean hasMissingStartupPermissions() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                return true;
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return true;
        }

        return Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED;
    }

    private void requestStartupPermissions() {
        ArrayList<String> missing = new ArrayList<>();

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }

        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (missing.isEmpty()) {
            startBootstrap();
            return;
        }

        ActivityCompat.requestPermissions(
                this,
                missing.toArray(new String[0]),
                REQUEST_STARTUP_PERMISSIONS
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STARTUP_PERMISSIONS) {
            setState("Подготовка приложения", "Продолжить", true, this::startBootstrap);
        }
    }

    private void startBootstrap() {
        if (bootstrapStarted) {
            refreshState();
            return;
        }

        bootstrapStarted = true;
        setState("Подготовка окружения…", "Подождите", false, null);

        try {
            RootFSInstaller.installIfNeeded(this);
        } catch (Throwable t) {
            setState("Ошибка: " + safeMessage(t), "Повторить", true, this::retryBootstrap);
            return;
        }

        handler.postDelayed(this::refreshState, 500);
    }

    private void retryBootstrap() {
        bootstrapStarted = true;
        try {
            RootFSInstaller.installIfNeeded(this);
            handler.postDelayed(this::refreshState, 600);
        } catch (Throwable t) {
            setState("Ошибка: " + safeMessage(t), "Повторить", true, this::retryBootstrap);
        }
    }

    private void buildMainUi() {
        subPageVisible = false;

        rootView = new LinearLayout(this);
        rootView.setOrientation(LinearLayout.VERTICAL);
        rootView.setGravity(Gravity.TOP);
        rootView.setPadding(dp(24), dp(58), dp(24), dp(28));
        rootView.setBackgroundColor(Color.rgb(12, 13, 16));

        title = new TextView(this);
        title.setText("CS2 Mobile");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setGravity(Gravity.START);
        title.setPadding(dp(2), 0, 0, dp(8));
        rootView.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView version = new TextView(this);
        version.setText("0.4.7 alpha");
        version.setTextColor(Color.rgb(113, 118, 128));
        version.setTextSize(12);
        version.setPadding(dp(3), 0, 0, dp(34));
        rootView.addView(version, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(20), dp(18), dp(18));
        panel.setBackground(card(Color.rgb(22, 24, 29), 16));
        rootView.addView(panel, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setTextColor(Color.rgb(188, 193, 202));
        status.setTextSize(14);
        status.setGravity(Gravity.START);
        status.setPadding(dp(2), 0, dp(2), dp(18));
        panel.addView(status, new LinearLayout.LayoutParams(-1, -2));

        action = new Button(this);
        action.setAllCaps(false);
        action.setText("Подождите");
        action.setTextSize(16);
        action.setTextColor(Color.WHITE);
        action.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(53, 101, 224)));
        action.setEnabled(false);
        panel.addView(action, new LinearLayout.LayoutParams(-1, dp(54)));

        controlsButton = makeButton("Управление");
        controlsButton.setOnClickListener(v -> showControlsSettings());
        LinearLayout.LayoutParams controlsLp = new LinearLayout.LayoutParams(-1, dp(50));
        controlsLp.topMargin = dp(10);
        panel.addView(controlsButton, controlsLp);

        settingsButton = makeButton("Настройки");
        settingsButton.setOnClickListener(v -> showAppSettings());
        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(-1, dp(50));
        settingsLp.topMargin = dp(8);
        panel.addView(settingsButton, settingsLp);

        TextView footer = new TextView(this);
        footer.setText("CS2 устанавливается через Steam");
        footer.setTextColor(Color.rgb(88, 93, 103));
        footer.setTextSize(11);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(18), 0, 0);
        rootView.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        setContentView(rootView);
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(15);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(41, 44, 52)));
        return button;
    }

    private GradientDrawable card(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), Color.rgb(35, 38, 45));
        return drawable;
    }

    private void animateIn() {
        if (rootView == null) return;

        rootView.setAlpha(0f);
        rootView.setTranslationY(dp(10));
        rootView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(260)
                .start();

        if (title != null) {
            title.setAlpha(0f);
            title.setTranslationY(dp(8));
            title.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(60)
                    .setDuration(240)
                    .start();
        }

        if (action != null) {
            action.setAlpha(0f);
            action.animate()
                    .alpha(1f)
                    .setStartDelay(120)
                    .setDuration(220)
                    .start();
        }
    }

    private void showAppSettings() {
        subPageVisible = true;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.TOP);
        root.setPadding(dp(24), dp(58), dp(24), dp(28));
        root.setBackgroundColor(Color.rgb(12, 13, 16));

        TextView header = new TextView(this);
        header.setText("Настройки");
        header.setTextColor(Color.WHITE);
        header.setTextSize(28);
        header.setPadding(dp(2), 0, 0, dp(26));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        Button permissions = makeButton("Разрешения Android");
        permissions.setOnClickListener(v -> requestStartupPermissions());
        root.addView(permissions, new LinearLayout.LayoutParams(-1, dp(52)));

        Button system = makeButton("Настройки приложения");
        system.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });
        LinearLayout.LayoutParams systemLp = new LinearLayout.LayoutParams(-1, dp(52));
        systemLp.topMargin = dp(10);
        root.addView(system, systemLp);

        Button back = makeButton("Назад");
        back.setOnClickListener(v -> {
            subPageVisible = false;
            buildMainUi();
            animateIn();
            if (bootstrapStarted) refreshState();
            else initializeState();
        });
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(-1, dp(52));
        backLp.topMargin = dp(20);
        root.addView(back, backLp);

        setContentView(root);
        root.setAlpha(0f);
        root.setTranslationY(dp(8));
        root.animate().alpha(1f).translationY(0f).setDuration(220).start();
    }

    private void showControlsSettings() {
        subPageVisible = true;

        ControlsProfile profile = ensureCs2ControlsProfile();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.TOP);
        root.setPadding(dp(24), dp(58), dp(24), dp(28));
        root.setBackgroundColor(Color.rgb(12, 13, 16));

        TextView header = new TextView(this);
        header.setText("Управление");
        header.setTextColor(Color.WHITE);
        header.setTextSize(28);
        header.setPadding(dp(2), 0, 0, dp(26));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        TextView sensitivityLabel = new TextView(this);
        sensitivityLabel.setTextColor(Color.rgb(210, 214, 221));
        sensitivityLabel.setTextSize(14);
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
        opacityLabel.setTextColor(Color.rgb(210, 214, 221));
        opacityLabel.setTextSize(14);
        opacityLabel.setPadding(0, dp(14), 0, 0);
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

        Button editor = makeButton("Редактор HUD");
        editor.setEnabled(profile != null);
        editor.setOnClickListener(v -> {
            ControlsProfile latest = ensureCs2ControlsProfile();
            if (latest == null) return;
            Intent intent = new Intent(this, ControlsEditorActivity.class);
            intent.putExtra("profile_id", latest.id);
            startActivity(intent);
        });
        LinearLayout.LayoutParams editorLp = new LinearLayout.LayoutParams(-1, dp(52));
        editorLp.topMargin = dp(18);
        root.addView(editor, editorLp);

        Button reset = makeButton("Сбросить раскладку");
        reset.setOnClickListener(v -> {
            resetCs2ControlsProfile();
            prefs.edit().putFloat(PREF_OVERLAY_OPACITY, 0.65f).apply();
            showControlsSettings();
        });
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(-1, dp(52));
        resetLp.topMargin = dp(10);
        root.addView(reset, resetLp);

        Button back = makeButton("Назад");
        back.setOnClickListener(v -> {
            subPageVisible = false;
            buildMainUi();
            animateIn();
            if (bootstrapStarted) refreshState();
            else initializeState();
        });
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(-1, dp(52));
        backLp.topMargin = dp(20);
        root.addView(back, backLp);

        setContentView(root);
        root.setAlpha(0f);
        root.setTranslationY(dp(8));
        root.animate().alpha(1f).translationY(0f).setDuration(220).start();
    }

    private void updateSensitivityLabel(TextView label, float speed) {
        label.setText(String.format(java.util.Locale.US, "Чувствительность: %.2fx", speed));
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
        if (subPageVisible) return;

        try {
            refreshStateInternal();
        } catch (Throwable t) {
            setState("Ошибка запуска: " + safeMessage(t), "Повторить", true, this::retryBootstrap);
        }
    }

    private void refreshStateInternal() {
        File bootstrapError = new File(getFilesDir(), "bootstrap-error.txt");
        if (bootstrapError.isFile()) {
            String error = FileUtils.readString(bootstrapError);
            if (error == null || error.trim().isEmpty()) error = "ошибка runtime";
            setState("Ошибка: " + error.trim(), "Повторить", true, () -> {
                bootstrapError.delete();
                retryBootstrap();
            });
            return;
        }

        RootFS rootFS = RootFS.find(this);
        if (!rootFS.isValid()) {
            setState("Подготовка окружения…", "Подождите", false, null);
            handler.postDelayed(this::refreshState, 1000);
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
                setState("Создание окружения…", "Подождите", false, null);
                createCs2Container(manager);
                return;
            } else {
                handler.postDelayed(this::refreshState, 700);
                return;
            }
        }

        ensureLauncherBats();

        File steam = steamExe();
        if (!steam.exists()) {
            setState("Steam не установлен", "Установить Steam", true,
                    () -> launchBat("install_steam.bat", "", false));
            return;
        }

        if (!cs2Manifest().exists()) {
            setState("CS2 не установлена", "Открыть Steam", true,
                    () -> launch(steam, "", false));
            return;
        }

        setState("Готово", "Запустить CS2", true,
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
            data.put("desktopTheme", "DARK,COLOR,#0c0d10");

            manager.createContainerAsync(data, created -> {
                creatingContainer = false;

                if (created == null) {
                    setState("Не удалось создать окружение", "Повторить", true, this::refreshState);
                    return;
                }

                container = created;
                runOnUiThread(this::refreshState);
            });
        } catch (Exception e) {
            creatingContainer = false;
            setState("Ошибка: " + safeMessage(e), "Повторить", true, this::refreshState);
        }
    }

    private void ensureLauncherBats() {
        File c = new File(container.getRootDir(), ".wine/drive_c");
        File install = new File(c, "install_steam.bat");

        if (!install.exists()) {
            write(
                    install,
                    "@echo off\r\nZ:\\opt\\apps\\winaddons.exe -n \"Steam (Legacy)\" -d \"Steam\" -e \"steam.exe\"\r\n"
            );
        }
    }

    private void launchBat(String name, String args, boolean controls) {
        File bat = new File(container.getRootDir(), ".wine/drive_c/" + name);
        launch(bat, args, controls);
    }

    private void launch(File exe, String args, boolean controls) {
        setState(controls ? "Запуск CS2…" : "Запуск Steam…", "Запуск…", false, null);

        Intent i = new Intent(this, XServerDisplayActivity.class);
        i.putExtra("container_id", container.id);
        i.putExtra("exec_path", exe.getAbsolutePath());

        if (args != null && !args.isEmpty()) {
            i.putExtra("exec_args", args);
        }

        if (controls) {
            ControlsProfile profile = ensureCs2ControlsProfile();
            if (profile != null) {
                i.putExtra("controls_profile_id", profile.id);
            }
        }

        Runnable start = () -> startActivity(i);

        if (rootView != null) {
            rootView.animate()
                    .alpha(0f)
                    .translationY(dp(6))
                    .setDuration(180)
                    .withEndAction(start)
                    .start();
        } else {
            start.run();
        }
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

            status.animate()
                    .alpha(0f)
                    .setDuration(80)
                    .withEndAction(() -> {
                        status.setText(text);
                        status.animate().alpha(1f).setDuration(140).start();
                    })
                    .start();

            action.setText(buttonText);
            action.setEnabled(enabled);
            action.setAlpha(enabled ? 1f : 0.58f);
            action.setOnClickListener(v -> {
                if (callback != null) callback.run();
            });
        });
    }

    private String safeMessage(Throwable t) {
        String msg = t.getMessage();
        return (msg == null || msg.trim().isEmpty()) ? t.getClass().getSimpleName() : msg;
    }

    private static void write(File file, String data) {
        try {
            file.getParentFile().mkdirs();
            java.nio.file.Files.write(
                    file.toPath(),
                    data.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );
        } catch (Exception ignored) {}
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
