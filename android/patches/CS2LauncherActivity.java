package com.winlator;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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

/** Single-purpose launcher: setup -> Steam -> Counter-Strike 2 (AppID 730). */
public class CS2LauncherActivity extends AppCompatActivity {
    private static final int REQUEST_STARTUP_PERMISSIONS = 730;
    private static final String CS2_PROFILE_NAME = "CS2 Mobile";
    private static final String PREF_PROFILE_ID = "cs2_controls_profile_id";
    private static final String PREF_OVERLAY_OPACITY = "overlay_opacity";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout rootView;
    private TextView title;
    private TextView status;
    private TextView stageText;
    private View pulseDot;
    private Button action;
    private Button controlsButton;
    private Container container;
    private boolean creatingContainer;
    private boolean controlsSettingsVisible;
    private boolean bootstrapStarted;
    private ObjectAnimator pulseAnimator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        AppUtils.keepScreenOn(this);

        buildUi();
        animateEntrance();

        // Do not rotate or start the heavy runtime immediately.
        // First show a normal launcher screen, then permissions/setup, and only later Steam/CS2.
        RootFS rootFS = RootFS.find(this);
        if (rootFS.isValid()) {
            bootstrapStarted = true;
            setStage("ОКРУЖЕНИЕ ГОТОВО");
            setState("Проверяем Steam и Counter-Strike 2…", "ПРОВЕРКА…", false, null);
            handler.postDelayed(this::refreshState, 350);
        } else {
            setStage("ДОБРО ПОЖАЛОВАТЬ");
            setState("Сначала настроим Android-разрешения и игровое окружение. CS2 запустится только после этого.",
                    "НАЧАТЬ НАСТРОЙКУ", true, this::ensurePermissionsThenBootstrap);
        }
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
            rootView.setScaleX(1f);
            rootView.setScaleY(1f);
        }
        if (!controlsSettingsVisible && bootstrapStarted && status != null) {
            handler.postDelayed(this::refreshState, 500);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (pulseAnimator != null) pulseAnimator.cancel();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (controlsSettingsVisible) {
            controlsSettingsVisible = false;
            buildUi();
            animateEntrance();
            handler.post(this::refreshState);
            return;
        }
        super.onBackPressed();
    }

    private void ensurePermissionsThenBootstrap() {
        ArrayList<String> missing = new ArrayList<>();

        // Winlator-style legacy storage access on Android versions where these permissions are meaningful.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }

        // Voice chat in CS2 needs microphone access. Denial does not block the app.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }

        // Foreground service notifications are useful while Steam/CS2 is running in the background.
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        // Voice chat in CS2 needs microphone access. It is requested during setup, not at game launch.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }

        if (!missing.isEmpty()) {
            setStage("РАЗРЕШЕНИЯ");
            setState("Разреши уведомления и микрофон для фоновой работы и голосового чата. Интернет доступен автоматически.",
                    "РАЗРЕШИТЬ", true,
                    () -> ActivityCompat.requestPermissions(
                            this,
                            missing.toArray(new String[0]),
                            REQUEST_STARTUP_PERMISSIONS));
            return;
        }

        startBootstrap();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STARTUP_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (!allGranted) {
                setStage("РАЗРЕШЕНИЯ ОГРАНИЧЕНЫ");
                setState("Часть разрешений не выдана. Продолжим, но фоновые функции или доступ к файлам могут быть ограничены.",
                        "ПРОДОЛЖИТЬ", true, this::startBootstrap);
            } else {
                setStage("РАЗРЕШЕНИЯ ГОТОВЫ");
                setState("Разрешения получены. Подготавливаем игровое окружение…",
                        "ПОДГОТОВКА…", false, null);
                handler.postDelayed(this::startBootstrap, 300);
            }
        }
    }

    private void startBootstrap() {
        if (bootstrapStarted) {
            refreshState();
            return;
        }
        bootstrapStarted = true;
        setStage("ПОДГОТОВКА RUNTIME");
        setState("Распаковываем игровое окружение. Первый запуск может занять немного времени.",
                "ПОДГОТОВКА…", false, null);

        try {
            RootFSInstaller.installIfNeeded(this);
        } catch (Throwable t) {
            setState("Ошибка подготовки окружения: " + safeMessage(t), "ПОВТОРИТЬ", true, this::retryBootstrap);
            return;
        }

        handler.postDelayed(this::refreshState, 450);
    }

    private void retryBootstrap() {
        bootstrapStarted = true;
        try {
            RootFSInstaller.installIfNeeded(this);
            handler.postDelayed(this::refreshState, 700);
        } catch (Throwable t) {
            setState("Ошибка подготовки окружения: " + safeMessage(t), "ПОВТОРИТЬ", true, this::retryBootstrap);
        }
    }

    private void buildUi() {
        controlsSettingsVisible = false;
        if (pulseAnimator != null) pulseAnimator.cancel();

        rootView = new LinearLayout(this);
        rootView.setOrientation(LinearLayout.VERTICAL);
        rootView.setGravity(Gravity.CENTER_HORIZONTAL);
        rootView.setPadding(dp(24), dp(44), dp(24), dp(32));

        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(10, 12, 16), Color.rgb(20, 22, 29), Color.rgb(10, 12, 16)});
        rootView.setBackground(bg);

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER);
        hero.setPadding(dp(20), dp(22), dp(20), dp(22));
        hero.setBackground(card(Color.rgb(24, 27, 35), 22));
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(-1, -2);
        heroLp.bottomMargin = dp(20);
        rootView.addView(hero, heroLp);

        TextView mark = new TextView(this);
        mark.setText("◎");
        mark.setTextColor(Color.rgb(242, 159, 5));
        mark.setTextSize(52);
        mark.setGravity(Gravity.CENTER);
        hero.addView(mark, new LinearLayout.LayoutParams(-1, -2));

        title = new TextView(this);
        title.setText("CS2 MOBILE");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER);
        title.setLetterSpacing(0.08f);
        hero.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(this);
        subtitle.setText("Steam → Counter-Strike 2 → мобильное управление");
        subtitle.setTextColor(Color.rgb(145, 153, 167));
        subtitle.setTextSize(13);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(6), 0, 0);
        hero.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout stageRow = new LinearLayout(this);
        stageRow.setOrientation(LinearLayout.HORIZONTAL);
        stageRow.setGravity(Gravity.CENTER_VERTICAL);
        stageRow.setPadding(dp(4), dp(4), dp(4), dp(12));
        rootView.addView(stageRow, new LinearLayout.LayoutParams(-1, -2));

        pulseDot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(Color.rgb(242, 159, 5));
        pulseDot.setBackground(dotBg);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(9), dp(9));
        dotLp.rightMargin = dp(9);
        stageRow.addView(pulseDot, dotLp);

        stageText = new TextView(this);
        stageText.setTextColor(Color.rgb(242, 159, 5));
        stageText.setTextSize(12);
        stageText.setLetterSpacing(0.05f);
        stageRow.addView(stageText, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(20), dp(22), dp(20), dp(22));
        panel.setBackground(card(Color.rgb(19, 22, 28), 20));
        rootView.addView(panel, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setTextColor(Color.rgb(205, 210, 218));
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setLineSpacing(0f, 1.08f);
        status.setPadding(0, 0, 0, dp(20));
        panel.addView(status, new LinearLayout.LayoutParams(-1, -2));

        action = new Button(this);
        action.setAllCaps(false);
        action.setTextSize(16);
        action.setTextColor(Color.BLACK);
        action.setText("ПОДГОТОВКА…");
        action.setEnabled(false);
        action.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(242, 159, 5)));
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(-1, dp(56));
        panel.addView(action, actionLp);

        controlsButton = new Button(this);
        controlsButton.setAllCaps(false);
        controlsButton.setText("УПРАВЛЕНИЕ");
        controlsButton.setTextSize(15);
        controlsButton.setTextColor(Color.WHITE);
        controlsButton.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(45, 49, 61)));
        controlsButton.setOnClickListener(v -> showControlsSettings());
        LinearLayout.LayoutParams controlsLp = new LinearLayout.LayoutParams(-1, dp(52));
        controlsLp.topMargin = dp(10);
        panel.addView(controlsButton, controlsLp);

        TextView note = new TextView(this);
        note.setText("Steam-вход выполняет сам Steam. Игра скачивается из Steam и не входит в APK.");
        note.setTextColor(Color.rgb(104, 111, 124));
        note.setTextSize(12);
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(12), dp(18), dp(12), 0);
        rootView.addView(note, new LinearLayout.LayoutParams(-1, -2));

        setContentView(rootView);
        startPulse();
    }

    private GradientDrawable card(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), Color.rgb(38, 42, 52));
        return drawable;
    }

    private void animateEntrance() {
        if (rootView == null) return;
        rootView.setAlpha(0f);
        rootView.setTranslationY(dp(18));
        rootView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(420)
                .start();

        if (title != null) {
            title.setAlpha(0f);
            title.setScaleX(0.92f);
            title.setScaleY(0.92f);
            title.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setStartDelay(120)
                    .setDuration(420)
                    .start();
        }

        if (action != null) {
            action.setAlpha(0f);
            action.setTranslationY(dp(20));
            action.animate().alpha(1f).translationY(0f).setStartDelay(220).setDuration(360).start();
        }

        if (controlsButton != null) {
            controlsButton.setAlpha(0f);
            controlsButton.setTranslationY(dp(20));
            controlsButton.animate().alpha(1f).translationY(0f).setStartDelay(280).setDuration(360).start();
        }
    }

    private void startPulse() {
        if (pulseDot == null) return;
        pulseAnimator = ObjectAnimator.ofFloat(pulseDot, View.ALPHA, 0.25f, 1f, 0.25f);
        pulseAnimator.setDuration(1500);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.start();
    }

    private void setStage(String text) {
        if (stageText != null) {
            stageText.animate().alpha(0f).setDuration(100).withEndAction(() -> {
                stageText.setText(text);
                stageText.animate().alpha(1f).setDuration(180).start();
            }).start();
        }
    }

    private void showControlsSettings() {
        controlsSettingsVisible = true;
        ControlsProfile profile = ensureCs2ControlsProfile();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(34), dp(24), dp(28));

        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(10, 12, 16), Color.rgb(20, 22, 29)});
        root.setBackground(bg);

        TextView header = new TextView(this);
        header.setText("УПРАВЛЕНИЕ");
        header.setTextColor(Color.WHITE);
        header.setTextSize(27);
        header.setGravity(Gravity.CENTER);
        header.setPadding(0, 0, 0, dp(8));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = new TextView(this);
        hint.setText("Настрой HUD как в мобильном шутере: позиция, размер, прозрачность и назначение кнопок.");
        hint.setTextColor(Color.rgb(165, 173, 187));
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(8), 0, dp(8), dp(22));
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(18), dp(18), dp(18));
        panel.setBackground(card(Color.rgb(19, 22, 28), 20));
        root.addView(panel, new LinearLayout.LayoutParams(-1, -2));

        TextView sensitivityLabel = new TextView(this);
        sensitivityLabel.setTextColor(Color.WHITE);
        sensitivityLabel.setTextSize(15);
        panel.addView(sensitivityLabel, new LinearLayout.LayoutParams(-1, -2));

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
        panel.addView(sensitivity, new LinearLayout.LayoutParams(-1, -2));

        TextView opacityLabel = new TextView(this);
        opacityLabel.setTextColor(Color.WHITE);
        opacityLabel.setTextSize(15);
        opacityLabel.setPadding(0, dp(14), 0, 0);
        panel.addView(opacityLabel, new LinearLayout.LayoutParams(-1, -2));

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
        panel.addView(opacity, new LinearLayout.LayoutParams(-1, -2));

        Button editor = makeSecondaryButton("РЕДАКТОР HUD");
        editor.setEnabled(profile != null);
        editor.setOnClickListener(v -> {
            ControlsProfile latest = ensureCs2ControlsProfile();
            if (latest == null) return;
            Intent intent = new Intent(this, ControlsEditorActivity.class);
            intent.putExtra("profile_id", latest.id);
            startActivity(intent);
        });
        LinearLayout.LayoutParams editorLp = new LinearLayout.LayoutParams(-1, dp(54));
        editorLp.topMargin = dp(18);
        panel.addView(editor, editorLp);

        Button reset = makeSecondaryButton("СБРОСИТЬ РАСКЛАДКУ");
        reset.setOnClickListener(v -> {
            resetCs2ControlsProfile();
            prefs.edit().putFloat(PREF_OVERLAY_OPACITY, 0.65f).apply();
            showControlsSettings();
        });
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(-1, dp(50));
        resetLp.topMargin = dp(10);
        panel.addView(reset, resetLp);

        Button back = makeSecondaryButton("НАЗАД");
        back.setOnClickListener(v -> {
            controlsSettingsVisible = false;
            buildUi();
            animateEntrance();
            refreshState();
        });
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(-1, dp(50));
        backLp.topMargin = dp(10);
        panel.addView(back, backLp);

        setContentView(root);
        root.setAlpha(0f);
        root.setTranslationY(dp(16));
        root.animate().alpha(1f).translationY(0f).setDuration(330).start();
    }

    private Button makeSecondaryButton(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(15);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(45, 49, 61)));
        return button;
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

        try {
            refreshStateInternal();
        } catch (Throwable t) {
            setStage("ОШИБКА");
            setState("Ошибка запуска: " + safeMessage(t), "ПОВТОРИТЬ", true, this::retryBootstrap);
        }
    }

    private void refreshStateInternal() {
        File bootstrapError = new File(getFilesDir(), "bootstrap-error.txt");
        if (bootstrapError.isFile()) {
            String error = FileUtils.readString(bootstrapError);
            if (error == null || error.trim().isEmpty()) error = "неизвестная ошибка runtime";
            setStage("ОШИБКА RUNTIME");
            setState("Ошибка подготовки: " + error.trim(), "ПОВТОРИТЬ", true, () -> {
                bootstrapError.delete();
                retryBootstrap();
            });
            return;
        }

        RootFS rootFS = RootFS.find(this);
        if (!rootFS.isValid()) {
            setStage("ПОДГОТОВКА RUNTIME");
            setState("Распаковываем игровое окружение…", "ПОДОЖДАТЬ", false, null);
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
                setStage("СОЗДАНИЕ КОНТЕЙНЕРА");
                setState("Создаём окружение CS2…", "ПОДОЖДАТЬ", false, null);
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
            setStage("STEAM");
            setState("Окружение готово. Теперь установим Steam внутри приложения.",
                    "УСТАНОВИТЬ STEAM", true,
                    () -> launchBat("install_steam.bat", "", false));
            return;
        }

        if (!cs2Manifest().exists()) {
            setStage("STEAM ГОТОВ");
            setState("Войди в Steam и установи Counter-Strike 2 (AppID 730).",
                    "ОТКРЫТЬ STEAM", true,
                    () -> launch(steam, "", false));
            return;
        }

        setStage("ГОТОВО К ИГРЕ");
        setState("CS2 установлен. При запуске экран перейдёт в горизонтальный игровой режим.",
                "ИГРАТЬ", true,
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
                creatingContainer = false;
                if (created == null) {
                    setStage("ОШИБКА");
                    setState("Не удалось создать контейнер CS2.", "ПОВТОРИТЬ", true, this::refreshState);
                    return;
                }
                container = created;
                runOnUiThread(this::refreshState);
            });
        } catch (Exception e) {
            creatingContainer = false;
            setStage("ОШИБКА");
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
        setStage(controls ? "ЗАПУСК CS2" : "ЗАПУСК STEAM");
        setState(controls ? "Запускаем Counter-Strike 2…" : "Открываем Steam…", "ЗАПУСК…", false, null);

        Intent i = new Intent(this, XServerDisplayActivity.class);
        i.putExtra("container_id", container.id);
        i.putExtra("exec_path", exe.getAbsolutePath());
        if (args != null && !args.isEmpty()) i.putExtra("exec_args", args);
        if (controls) {
            ControlsProfile profile = ensureCs2ControlsProfile();
            if (profile != null) i.putExtra("controls_profile_id", profile.id);
        }

        Runnable start = () -> startActivity(i);
        if (rootView != null) {
            rootView.animate()
                    .alpha(0f)
                    .scaleX(0.985f)
                    .scaleY(0.985f)
                    .setDuration(220)
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

            status.animate().alpha(0f).setDuration(90).withEndAction(() -> {
                status.setText(text);
                status.animate().alpha(1f).setDuration(180).start();
            }).start();

            action.setText(buttonText);
            action.setEnabled(enabled);
            action.setAlpha(enabled ? 1f : 0.62f);
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
            java.nio.file.Files.write(file.toPath(), data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
