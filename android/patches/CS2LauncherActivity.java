package com.winlator;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.GraphicsDrivers;
import com.winlator.core.AppUtils;
import com.winlator.xenvironment.RootFS;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;

/** Single-purpose launcher: Steam bootstrap -> Counter-Strike 2 (AppID 730). */
public class CS2LauncherActivity extends MainActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView title;
    private TextView status;
    private Button action;
    private Container container;
    private boolean creatingContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppUtils.hideSystemUI(this);
        buildUi();
        handler.post(this::refreshState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (status != null) handler.postDelayed(this::refreshState, 500);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(28), dp(28), dp(28));
        root.setBackgroundColor(Color.rgb(12, 14, 18));

        title = new TextView(this);
        title.setText("COUNTER-STRIKE 2");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(280), dp(58));
        root.addView(action, lp);

        TextView note = new TextView(this);
        note.setText("Steam авторизация выполняется самим Steam. Игра скачивается из Steam и не входит в APK.");
        note.setTextColor(Color.rgb(115, 123, 136));
        note.setTextSize(12);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(22), 0, 0);
        root.addView(note, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
    }

    private void refreshState() {
        RootFS rootFS = RootFS.find(this);
        if (!rootFS.isValid()) {
            setState("Подготовка игрового окружения…", "ПОДОЖДАТЬ", false, null);
            handler.postDelayed(this::refreshState, 1200);
            return;
        }

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

        setState("CS2 установлен · мобильное управление готово", "ИГРАТЬ", true,
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
        if (controls) i.putExtra("controls_profile_id", 4); // bundled FPS profile
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
