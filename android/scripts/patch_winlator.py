#!/usr/bin/env python3
from pathlib import Path
import re, sys

root = Path(sys.argv[1]).resolve()
launcher_src = Path(__file__).resolve().parents[1] / "patches" / "CS2LauncherActivity.java"
icon_src = Path(__file__).resolve().parents[1] / "patches" / "ic_cs2_mobile.xml"
java_dir = root / "app/src/main/java/com/winlator"
(java_dir / "CS2LauncherActivity.java").write_text(launcher_src.read_text(encoding="utf-8"), encoding="utf-8")
drawable_dir = root / "app/src/main/res/drawable"
(drawable_dir / "ic_cs2_mobile.xml").write_text(icon_src.read_text(encoding="utf-8"), encoding="utf-8")

# Brand/package metadata while keeping the Java namespace com.winlator for upstream compatibility.
gradle = root / "app/build.gradle"
s = gradle.read_text(encoding="utf-8")
s = s.replace("applicationId 'com.winlator'", "applicationId 'com.reverfyx.cs2mobile'")
s = s.replace('versionCode 33', 'versionCode 43')
s = s.replace('versionName "11.2"', 'versionName "0.4.3-alpha"')
gradle.write_text(s, encoding="utf-8")

# Replace Winlator branding in every localized string table so Android cannot pick an old localized app name.
for strings in (root / "app/src/main/res").glob("values*/strings.xml"):
    s = strings.read_text(encoding="utf-8")
    s = re.sub(r'<string name="app_name">.*?</string>', '<string name="app_name">CS2 Mobile</string>', s, count=1)
    s = s.replace("Winlator", "CS2 Mobile")
    strings.write_text(s, encoding="utf-8")

manifest = root / "app/src/main/AndroidManifest.xml"
s = manifest.read_text(encoding="utf-8")
# Remove launcher intent-filter from upstream MainActivity, then add dedicated launcher activity.
main_pattern = re.compile(r'(\s*<activity android:name="com\.winlator\.MainActivity".*?</activity>)', re.S)
m = main_pattern.search(s)
if not m:
    raise SystemExit("MainActivity block not found")
main_block = m.group(1)
main_block = re.sub(r'\s*<intent-filter>.*?</intent-filter>', '', main_block, flags=re.S)
s = s[:m.start()] + main_block + s[m.end():]
launcher_block = '''
        <activity android:name="com.winlator.CS2LauncherActivity"
            android:theme="@style/AppThemeFullscreenDark"
            android:exported="true"
            android:screenOrientation="sensorLandscape"
            android:configChanges="keyboard|keyboardHidden|orientation|screenSize|screenLayout|smallestScreenSize|density|navigation">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
'''
s = s.replace('        <activity android:name="com.winlator.XServerDisplayActivity"', launcher_block + '\n        <activity android:name="com.winlator.XServerDisplayActivity"', 1)
s = s.replace('android:authorities="com.winlator.FileProvider"', 'android:authorities="com.reverfyx.cs2mobile.FileProvider"')
s = s.replace('android:icon="@mipmap/ic_launcher"', 'android:icon="@drawable/ic_cs2_mobile"\n        android:roundIcon="@drawable/ic_cs2_mobile"')
manifest.write_text(s, encoding="utf-8")

# Let the single-purpose launcher pass command-line arguments and force the selected touch profile.
xfile = java_dir / "XServerDisplayActivity.java"
x = xfile.read_text(encoding="utf-8")
old = '''            if (intent.hasExtra("exec_path")) {
                execPath = WineUtils.unixToDOSPath(intent.getStringExtra("exec_path"), container);

                if (execPath.endsWith(".lnk")) {'''
new = '''            if (intent.hasExtra("exec_path")) {
                execPath = WineUtils.unixToDOSPath(intent.getStringExtra("exec_path"), container);
                String intentExecArgs = intent.getStringExtra("exec_args");
                if (intentExecArgs != null && !intentExecArgs.isEmpty()) execArgs = " " + intentExecArgs;

                if (execPath.endsWith(".lnk")) {'''
if old not in x:
    raise SystemExit("exec_path patch seam not found")
x = x.replace(old, new, 1)

old2 = '''        if (shortcut != null) {
            String controlsProfile = shortcut.getExtra("controlsProfile");
            if (!controlsProfile.isEmpty()) {
                ControlsProfile profile = inputControlsManager.getProfile(Integer.parseInt(controlsProfile));
                if (profile != null) showInputControls(profile);
            }
        }

        if (MainActivity.DEBUG_MODE)'''
new2 = '''        if (shortcut != null) {
            String controlsProfile = shortcut.getExtra("controlsProfile");
            if (!controlsProfile.isEmpty()) {
                ControlsProfile profile = inputControlsManager.getProfile(Integer.parseInt(controlsProfile));
                if (profile != null) showInputControls(profile);
            }
        }

        int forcedControlsProfile = getIntent().getIntExtra("controls_profile_id", 0);
        if (forcedControlsProfile > 0) {
            ControlsProfile forcedProfile = inputControlsManager.getProfile(forcedControlsProfile);
            if (forcedProfile != null) {
                xServer.setRelativeMouseMovement(true);
                showInputControls(forcedProfile);
            }
        }

        if (MainActivity.DEBUG_MODE)'''
if old2 not in x:
    raise SystemExit("controls profile patch seam not found")
x = x.replace(old2, new2, 1)
xfile.write_text(x, encoding="utf-8")

print("Patched Winlator for CS2-only Android launcher")


# Decouple rootfs installation from Winlator MainActivity so our launcher is a normal AppCompatActivity.
rfi = java_dir / "xenvironment/RootFSInstaller.java"
rs = rfi.read_text(encoding="utf-8")
rs = rs.replace("public static void install(final MainActivity activity)", "public static void install(final AppCompatActivity activity)")
rs = rs.replace("public static void installIfNeeded(final MainActivity activity)", "public static void installIfNeeded(final AppCompatActivity activity)")
rfi.write_text(rs, encoding="utf-8")


# The upstream runtime hardcodes its original applicationId in a few filesystem/socket paths.
# Our APK uses com.reverfyx.cs2mobile, so all runtime paths must follow that package.
runtime_path_files = [
    root / "app/src/main/java/com/winlator/core/AppUtils.java",
    root / "app/src/main/cpp/winlator/include/winlator.h",
    root / "app/src/main/cpp/vortekrenderer/include/vortek.h",
    root / "app/src/main/cpp/gladiorenderer/include/gladio.h",
]
for path in runtime_path_files:
    data = path.read_text(encoding="utf-8")
    data = data.replace("/data/data/com.winlator", "/data/data/com.reverfyx.cs2mobile")
    path.write_text(data, encoding="utf-8")

file_utils = java_dir / "core/FileUtils.java"
fu = file_utils.read_text(encoding="utf-8")
fu = fu.replace('"com.winlator.FileProvider"', '"com.reverfyx.cs2mobile.FileProvider"')
file_utils.write_text(fu, encoding="utf-8")

# A previous interrupted first launch may leave an empty/corrupt .rfs_version file.
# Treat it as version 0 instead of throwing IndexOutOfBounds/NumberFormatException.
rootfs_file = java_dir / "xenvironment/RootFS.java"
rf = rootfs_file.read_text(encoding="utf-8")
old_get_version = '''    public int getVersion() {
        File rfsVersionFile = getRFSVersionFile();
        return rfsVersionFile.exists() ? Integer.parseInt(FileUtils.readLines(rfsVersionFile).get(0)) : 0;
    }'''
new_get_version = '''    public int getVersion() {
        File rfsVersionFile = getRFSVersionFile();
        if (!rfsVersionFile.exists()) return 0;
        try {
            java.util.ArrayList<String> lines = FileUtils.readLines(rfsVersionFile, true);
            if (lines.isEmpty()) return 0;
            return Integer.parseInt(lines.get(0).trim());
        }
        catch (Throwable t) {
            return 0;
        }
    }'''
if old_get_version not in rf:
    raise SystemExit("RootFS.getVersion patch seam not found")
rf = rf.replace(old_get_version, new_get_version, 1)
rootfs_file.write_text(rf, encoding="utf-8")

# Do not kill the Android process if rootfs extraction fails on a worker thread.
rfi = java_dir / "xenvironment/RootFSInstaller.java"
rs = rfi.read_text(encoding="utf-8")
old_worker = '''        Executors.newSingleThreadExecutor().execute(() -> {
            clearRootDir(rootDir);
            final long contentLength = TarCompressorUtils.getContentLength(TarCompressorUtils.Type.ZSTD, activity, FILENAME, rootDir);
            AtomicLong totalSizeRef = new AtomicLong();

            boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, activity, FILENAME, rootDir, (file, size) -> {
                if (size > 0) {
                    long totalSize = totalSizeRef.addAndGet(size);
                    final int progress = (int)(((float)totalSize / contentLength) * 100);
                    activity.runOnUiThread(() -> dialog.setProgress(progress));
                }
                return file;
            });

            if (success) {
                rootFS.createRFSVersionFile(LATEST_VERSION);
                resetContainerRFSVersions(activity);
            }
            else AppUtils.showToast(activity, R.string.unable_to_install_system_files);

            dialog.closeOnUiThread();
        });'''
new_worker = '''        Executors.newSingleThreadExecutor().execute(() -> {
            File errorFile = new File(activity.getFilesDir(), "bootstrap-error.txt");
            errorFile.delete();
            try {
                clearRootDir(rootDir);
                final long contentLength = TarCompressorUtils.getContentLength(TarCompressorUtils.Type.ZSTD, activity, FILENAME, rootDir);
                AtomicLong totalSizeRef = new AtomicLong();

                boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, activity, FILENAME, rootDir, (file, size) -> {
                    if (size > 0 && contentLength > 0) {
                        long totalSize = totalSizeRef.addAndGet(size);
                        final int progress = (int)(((float)totalSize / contentLength) * 100);
                        activity.runOnUiThread(() -> dialog.setProgress(progress));
                    }
                    return file;
                });

                if (success) {
                    rootFS.createRFSVersionFile(LATEST_VERSION);
                    resetContainerRFSVersions(activity);
                }
                else {
                    FileUtils.writeString(errorFile, "Не удалось распаковать системные файлы");
                    AppUtils.showToast(activity, R.string.unable_to_install_system_files);
                }
            }
            catch (Throwable t) {
                String message = t.getMessage();
                if (message == null || message.isEmpty()) message = t.getClass().getSimpleName();
                FileUtils.writeString(errorFile, t.getClass().getSimpleName()+": "+message);
            }
            finally {
                dialog.closeOnUiThread();
            }
        });'''
if old_worker not in rs:
    raise SystemExit("RootFSInstaller worker patch seam not found")
rs = rs.replace(old_worker, new_worker, 1)
rfi.write_text(rs, encoding="utf-8")
