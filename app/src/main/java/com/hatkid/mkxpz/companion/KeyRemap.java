package com.hatkid.mkxpz.companion;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

import org.libsdl.app.SDLActivity;

/**
 * Remaps physical buttons (Android key codes) to keyboard keys the game
 * understands. Mapped buttons are consumed and replayed as keyboard presses
 * through SDL, so they work the same as the on-screen gamepad. Buttons that
 * aren't mapped keep mkxp-z's default controller bindings.
 *
 * Also handles "capture" mode: the next physical button press is reported to
 * the companion UI instead of the game, so the user can pick what to remap.
 */
public final class KeyRemap
{
    private static final String TAG = "rejuv-dual[remap]";
    private static final String PREFS = "rejuv_dual_remap";
    private static final String KEY = "map";

    /** Target meaning "do nothing". */
    public static final int NONE = -1;

    /**
     * Built-in mapping for controller buttons, used for any button the user
     * hasn't remapped. Targets are the game's keyboard keys (mkxp-z RGSS1
     * defaults): C confirm, X cancel/menu, Z = A, A = X, S = Y, D = Z,
     * Q = L, W = R, F5 = registered item.
     */
    private static final Map<Integer, Integer> DEFAULTS = new HashMap<>();
    static {
        DEFAULTS.put(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_C);
        DEFAULTS.put(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_X);
        DEFAULTS.put(KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_Z);
        DEFAULTS.put(KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_A);
        DEFAULTS.put(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_Q);
        DEFAULTS.put(KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_W);
        DEFAULTS.put(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_S);
        DEFAULTS.put(KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_D);
        DEFAULTS.put(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_X);
        DEFAULTS.put(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_F5);
    }

    private static final Map<Integer, Integer> sMap = new HashMap<>();
    private static SharedPreferences sPrefs;
    private static volatile boolean sCapturing = false;
    /** Remapped keys currently held, so key-up goes to the same target. */
    private static final Map<Integer, Integer> sHeld = new HashMap<>();

    private KeyRemap() {}

    public static synchronized void init(Context ctx)
    {
        sPrefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sMap.clear();
        try {
            JSONObject o = new JSONObject(sPrefs.getString(KEY, "{}"));
            JSONArray names = o.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String k = names.getString(i);
                    sMap.put(Integer.parseInt(k), o.getInt(k));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Couldn't load remap: " + e);
        }
        Log.i(TAG, "Loaded " + sMap.size() + " button mapping(s)");
    }

    private static void save()
    {
        if (sPrefs == null)
            return;
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<Integer, Integer> e : sMap.entrySet())
                o.put(String.valueOf(e.getKey()), e.getValue());
            sPrefs.edit().putString(KEY, o.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "Couldn't save remap: " + e);
        }
    }

    public static synchronized void set(int physical, int target)
    {
        sMap.put(physical, target);
        save();
        Log.i(TAG, "Mapped " + KeyEvent.keyCodeToString(physical) + " -> " + target);
    }

    /** Back to the built-in default for this button (or unmapped). */
    public static synchronized void clear(int physical)
    {
        sMap.remove(physical);
        save();
    }

    public static synchronized void reset()
    {
        sMap.clear();
        save();
    }

    private static Integer lookup(int code)
    {
        Integer t = sMap.get(code);
        return t != null ? t : DEFAULTS.get(code);
    }

    /** Effective mappings (defaults + user changes) as JSON for the UI. */
    public static synchronized String toJson()
    {
        JSONArray a = new JSONArray();
        try {
            java.util.TreeSet<Integer> codes = new java.util.TreeSet<>(DEFAULTS.keySet());
            codes.addAll(sMap.keySet());
            for (int code : codes) {
                JSONObject o = new JSONObject();
                o.put("code", code);
                o.put("name", prettyName(code));
                o.put("target", lookup(code));
                o.put("custom", sMap.containsKey(code));
                a.put(o);
            }
        } catch (Exception ignored) {}
        return a.toString();
    }

    public static void startCapture()
    {
        sCapturing = true;
    }

    public static void cancelCapture()
    {
        sCapturing = false;
    }

    public static String prettyName(int keycode)
    {
        String s = KeyEvent.keyCodeToString(keycode);
        if (s.startsWith("KEYCODE_"))
            s = s.substring(8);
        return s.replace('_', ' ');
    }

    private static boolean isSystemKey(int keycode)
    {
        return keycode == KeyEvent.KEYCODE_VOLUME_UP
            || keycode == KeyEvent.KEYCODE_VOLUME_DOWN
            || keycode == KeyEvent.KEYCODE_VOLUME_MUTE
            || keycode == KeyEvent.KEYCODE_POWER
            || keycode == KeyEvent.KEYCODE_HOME
            || keycode == KeyEvent.KEYCODE_APP_SWITCH;
    }

    /**
     * Called first from MainActivity.dispatchKeyEvent. Returns true when the
     * event was handled (captured or remapped) and must not reach the game.
     */
    public static boolean handle(KeyEvent evt)
    {
        int code = evt.getKeyCode();
        if (isSystemKey(code))
            return false;

        if (sCapturing) {
            if (evt.getAction() == KeyEvent.ACTION_DOWN && evt.getRepeatCount() == 0) {
                sCapturing = false;
                CompanionBridge.fromGame("{\"t\":\"capture\",\"code\":" + code
                        + ",\"name\":" + JSONObject.quote(prettyName(code)) + "}");
            }
            return true;
        }

        Integer target;
        synchronized (KeyRemap.class) {
            target = lookup(code);
            if (target == null && evt.getAction() == KeyEvent.ACTION_UP)
                target = sHeld.get(code);
        }
        if (target == null)
            return false;

        if (target != NONE) {
            if (evt.getAction() == KeyEvent.ACTION_DOWN) {
                if (evt.getRepeatCount() == 0) {
                    synchronized (KeyRemap.class) { sHeld.put(code, target); }
                    SDLActivity.onNativeKeyDown(target);
                }
            } else if (evt.getAction() == KeyEvent.ACTION_UP) {
                synchronized (KeyRemap.class) {
                    Integer held = sHeld.remove(code);
                    if (held != null)
                        target = held;
                }
                SDLActivity.onNativeKeyUp(target);
            }
        }
        return true;
    }
}
