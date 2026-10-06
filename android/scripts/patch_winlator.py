#!/usr/bin/env python3
from pathlib import Path
import re, sys

root = Path(sys.argv[1]).resolve()
launcher_src = Path(__file__).resolve().parents[1] / "patches" / "CS2LauncherActivity.java"
java_dir = root / "app/src/main/java/com/winlator"
(java_dir / "CS2LauncherActivity.java").write_text(launcher_src.read_text(encoding="utf-8"), encoding="utf-8")

# Brand/package metadata while keeping the Java namespace com.winlator for upstream compatibility.
gradle = root / "app/build.gradle"
s = gradle.read_text(encoding="utf-8")
s = s.replace("applicationId 'com.winlator'", "applicationId 'com.reverfyx.cs2mobile'")
s = s.replace('versionCode 33', 'versionCode 41')
s = s.replace('versionName "11.2"', 'versionName "0.4.1-alpha"')
gradle.write_text(s, encoding="utf-8")

strings = root / "app/src/main/res/values/strings.xml"
s = strings.read_text(encoding="utf-8")
s = re.sub(r'<string name="app_name">.*?</string>', '<string name="app_name">CS2 Mobile</string>', s, count=1)
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
