package com.hatkid.mkxpz;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.InputDevice;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.os.storage.StorageManager;
import android.os.storage.OnObbStateChangeListener;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import java.util.Locale;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import org.libsdl.app.SDLActivity;
import com.hatkid.mkxpz.gamepad.Gamepad;
import com.hatkid.mkxpz.gamepad.GamepadConfig;
import com.hatkid.mkxpz.companion.CompanionBridge;
import com.hatkid.mkxpz.companion.CompanionHost;
import com.hatkid.mkxpz.companion.LogCapture;
import com.hatkid.mkxpz.companion.KeyRemap;

public class MainActivity extends SDLActivity
{
    // This activity inherits from SDLActivity activity.
    // Put your Java-side stuff here.

    private static final String TAG = "mkxp-z[Activity]";
    private static final String GAME_PATH_DEFAULT = Environment.getExternalStorageDirectory() + "/mkxp-z";
    private static String GAME_PATH = GAME_PATH_DEFAULT;
    private static String OBB_MAIN_FILENAME;
    private static boolean DEBUG = false;

    protected boolean mStarted = false;

    private StorageManager mStorageManager;

    // In-screen gamepad
    private final Gamepad mGamepad = new Gamepad();
    private boolean mGamepadInvisible = false;

    // rejuv-dual: second-screen companion UI
    private CompanionHost mCompanion;

    private static boolean hasStorageAccess()
    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            return Environment.isExternalStorageManager();
        return true;
    }

    /** Mirror the log into the game folder once we may write there. */
    private void setupLogFile()
    {
        File target = hasStorageAccess()
                ? new File(GAME_PATH_DEFAULT, "rejuv-dual/log.txt")
                : new File(getExternalFilesDir(null), "log.txt");
        LogCapture.setFile(target);
    }

    /** Startup health report, visible on the companion's Info tab. */
    private void logStartupReport()
    {
        Log.i(TAG, "rejuv-dual startup: device=" + Build.MANUFACTURER + " " + Build.MODEL
                + " android=" + Build.VERSION.SDK_INT);
        Log.i(TAG, "All files access: " + hasStorageAccess());
        File game = new File(GAME_PATH);
        Log.i(TAG, "Game path: " + GAME_PATH + " exists=" + game.isDirectory() + " readable=" + game.canRead());
        String[] entries = game.list();
        if (entries == null) {
            Log.w(TAG, "Game folder is not listable (missing, or no storage access)");
        } else {
            java.util.Arrays.sort(entries);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < entries.length && i < 40; i++)
                sb.append(entries[i]).append(i + 1 < entries.length ? ", " : "");
            Log.i(TAG, "Game folder has " + entries.length + " entries: " + sb);
            for (String want : new String[] { "Game.ini", "mkxp.json", "Data", "Graphics", "Audio" })
                Log.i(TAG, "  " + want + ": " + (new File(game, want).exists() ? "found" : "MISSING"));
            File scripts = new File(game, "Data/Scripts.rxdata");
            Log.i(TAG, "  Data/Scripts.rxdata: " + (scripts.isFile() ? scripts.length() + " bytes" : "MISSING"));
        }
    }

    private void restartApp()
    {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            int flags = android.app.PendingIntent.FLAG_CANCEL_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 1, launch, flags);
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
            am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 500, pi);
        }
        System.exit(0);
    }

    private void runSDLThread()
    {
        if (!mStarted) {
            setupLogFile();
            logStartupReport();
        }

        mStarted = true;

        // Run (resume) native SDL thread
        if (mHasMultiWindow) {
            resumeNativeThread();
        }
    }

    OnObbStateChangeListener obbListener = new OnObbStateChangeListener()
    {
        @Override
        public void onObbStateChange(String path, int state)
        {
            super.onObbStateChange(path, state);

            Log.v(TAG, "OBB state of " + path + " changed to " + state);

            switch (state)
            {
                case OnObbStateChangeListener.MOUNTED:
                    String obbPath = mStorageManager.getMountedObbPath(path);
                    Log.v(TAG, "OBB " + path + " is mounted to " + obbPath);
                    GAME_PATH = obbPath;
                    break;

                case OnObbStateChangeListener.UNMOUNTED:
                    Log.v(TAG, "OBB " + path + " is unmounted");
                    GAME_PATH = GAME_PATH_DEFAULT;
                    break;

                default:
                    Log.e(TAG, "Failed to mount OBB " + path + ": Got state " + state);
                    break;
            }

            runSDLThread();
        }
    };

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        if (requestCode == 110) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                // Close the App because the User did not allow the all files access permission to be used.
                mSingleton.finish();
            } else if (mStarted) {
                // The game already tried to start without file access and
                // can't recover in-process (the Ruby VM can't be restarted),
                // so relaunch the app now that access is granted.
                Log.i(TAG, "All files access granted, restarting app");
                android.widget.Toast.makeText(this, "File access granted, restarting…", android.widget.Toast.LENGTH_SHORT).show();
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::restartApp, 600);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        LogCapture.start();
        KeyRemap.init(getApplicationContext());

        super.onCreate(savedInstanceState);

        mStorageManager = (StorageManager) getSystemService(STORAGE_SERVICE);

        // Get main OBB filepath
        final String obbPrefix = "main"; // "main", "patch"
        final int obbVersion = 1;
        OBB_MAIN_FILENAME = getObbDir() + "/" + obbPrefix + "." + obbVersion + "." + getPackageName() + ".obb";

        // Get Debug flag
        try {
            ActivityInfo actInfo = getPackageManager().getActivityInfo(this.getComponentName(), PackageManager.GET_META_DATA);
            DEBUG = actInfo.metaData.getBoolean("mkxp_debug");
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Failed to set debug flag: " + e);
            e.printStackTrace();
        }

        // Check for all files access permission (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                // Request all files access permission
                // TODO: AlertDialog: polite notice that mkxp-z requires All Files Access permission.
                Uri uri = Uri.parse("package:" + getPackageName());
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri);
                startActivityForResult(intent, 110);
            }
        }

        // Setup in-screen gamepad
        mGamepadInvisible = (isAndroidTV() || isChromebook());
        GamepadConfig gpadConfig = new GamepadConfig();
        mGamepad.init(gpadConfig, mGamepadInvisible);
        mGamepad.setOnKeyDownListener(SDLActivity::onNativeKeyDown);
        mGamepad.setOnKeyUpListener(SDLActivity::onNativeKeyUp);

        if (mLayout != null) {
            mGamepad.attachTo(this, mLayout);

            // rejuv-dual: companion UI on the second screen (or a side panel)
            mCompanion = new CompanionHost(this);
            mCompanion.start(mLayout);
        }
    }

    @Override
    protected void onStart()
    {
        super.onStart();

        if (!mStarted) {
            // Check for main OBB file
            if (new File(OBB_MAIN_FILENAME).exists()) {
                Log.v(TAG, "Main OBB file found, starting with main OBB mount");

                // Try to mount main OBB file
                mStorageManager.mountObb(OBB_MAIN_FILENAME, null, obbListener);
            } else {
                Log.v(TAG, "Main OBB file not found, starting without main OBB mount");

                // Run from default game directory
                runSDLThread();
            }
        } else {
            // onStart: Resume SDL thread
            runSDLThread();
        }
    }

    @Override
    protected void onDestroy()
    {
        if (mCompanion != null) {
            mCompanion.stop();
            mCompanion = null;
        }

        super.onDestroy();

        // HACK: Exiting the JVM (process) since Ruby does not likes when we
        // trying to re-initialize Ruby VM in mkxp-z (JNI native library)
        // that leads to segmentation fault, even we have cleanup the Ruby VM.
        System.exit(0);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent evt)
    {
        // rejuv-dual: button remapping (and capture for the remap UI)
        if (KeyRemap.handle(evt)) {
            if (!mGamepadInvisible) {
                mGamepad.hideView();
                mGamepadInvisible = true;
            }
            return true;
        }

        if (
            evt.getKeyCode() != KeyEvent.KEYCODE_BACK &&
            evt.getKeyCode() != KeyEvent.KEYCODE_VOLUME_UP &&
            evt.getKeyCode() != KeyEvent.KEYCODE_VOLUME_DOWN &&
            evt.getKeyCode() != KeyEvent.KEYCODE_VOLUME_MUTE
        ) {
            // Hide gamepad view on key events when visible
            if (!mGamepadInvisible) {
                mGamepad.hideView();
                mGamepadInvisible = true;
            }
        }

        if (mGamepad.processGamepadEvent(evt))
            return true;

        return super.dispatchKeyEvent(evt);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent evt)
    {
        // Show gamepad view on touch when hidden
        if (mGamepadInvisible) {
            mGamepad.showView();
            mGamepadInvisible = false;
        }

        return super.dispatchTouchEvent(evt);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent evt)
    {
        if (mGamepad.processDPadEvent(evt))
            return true;

        return super.onGenericMotionEvent(evt);
    }

    /**
     * This method is for arguments for launching native mkxp-z.
     * 
     * @return arguments for the mkxp-z
     */
    @Override
    protected String[] getArguments()
    {
        String[] args;

        if (DEBUG) {
            // Arguments in Debug mode
            args = new String[] { "debug" };
        } else {
            // Arguments in normal mode
            args = new String[] {};
        }

        return args;
    }

    /**
     * This static method is used in native mkxp-z. (see systemImpl.cpp)
     * This method returns a string of current device locale tag. (e.g. "en_US")
     * 
     * @return string of locale tag
     */
    @SuppressWarnings("unused")
    private static String getSystemLanguage()
    {
        return Locale.getDefault().toString();
    }

    /**
     * This static method is used in native mkxp-z. (see android-binding.cpp)
     * This method returns a boolean indicating that the device has a vibrator or not.
     * 
     * @return boolean
     */
    @SuppressWarnings("unused")
    private static boolean hasVibrator()
    {
        Vibrator vib = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
        return vib.hasVibrator();
    }

    /**
     * This static method is used in native mkxp-z. (see android-binding.cpp)
     * This method makes device vibrating with given milliseconds duration.
     * 
     * @param duration milliseconds duration of vibration
     */
    @SuppressWarnings("unused")
    private static void vibrate(int duration)
    {
        Vibrator vib = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.EFFECT_HEAVY_CLICK));
        } else {
            vib.vibrate(duration);
        }
    }

    /**
     * This static method is used in native mkxp-z. (see android-binding.cpp)
     * This method turns off the current device vibration.
     */
    @SuppressWarnings("unused")
    private static void vibrateStop()
    {
        Vibrator vib = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
        vib.cancel();
    }

    /**
     * This static method is used in native mkxp-z. (see android-binding.cpp)
     * This method returns a boolean indicating the app is in multi window mode or not.
     * (Multi-window mode supports from Android 7.0 Nougat (API 24) and higher.)
     * 
     * @param activity current MainActivity instance
     * @return boolean
     */
    @SuppressWarnings("unused")
    private static boolean inMultiWindow(Activity activity)
    {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInMultiWindowMode();
    }

    // ── rejuv-dual companion bridge (called from android-binding.cpp) ──────

    /** Game -> companion UI. Called on the SDL thread. */
    @SuppressWarnings("unused")
    private static void companionPush(String json)
    {
        CompanionBridge.fromGame(json);
    }

    /** Companion UI -> game. Returns the next queued command or null. */
    @SuppressWarnings("unused")
    private static String companionPoll()
    {
        return CompanionBridge.poll();
    }

    /** True when a companion surface is attached. */
    @SuppressWarnings("unused")
    private static boolean companionActive()
    {
        return CompanionHost.isActive();
    }

    /**
     * Path of the companion Ruby mod to run before the game's scripts.
     * A copy at <game folder>/rejuv-dual/companion.rb wins (handy for
     * iterating without rebuilding the APK); otherwise the bundled asset is
     * extracted to internal storage.
     */
    @SuppressWarnings("unused")
    private static String companionScriptPath()
    {
        File override = new File(GAME_PATH, "rejuv-dual/companion.rb");
        if (override.isFile()) {
            Log.i(TAG, "Using companion override: " + override);
            return override.getAbsolutePath();
        }

        try {
            Context ctx = getContext();
            File dir = new File(ctx.getFilesDir(), "companion");
            if (!dir.isDirectory() && !dir.mkdirs())
                throw new java.io.IOException("mkdirs failed: " + dir);
            File out = new File(dir, "companion.rb");
            try (InputStream in = ctx.getAssets().open("companion/companion.rb");
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0)
                    os.write(buf, 0, n);
            }
            return out.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Couldn't extract companion.rb: " + e);
            return "";
        }
    }
}
