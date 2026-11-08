package com.hatkid.mkxpz.companion;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * WebView that renders the companion UI from app assets and exposes a tiny
 * JS interface ("RDNative") for sending commands back to the game.
 */
@SuppressLint("ViewConstructor")
public class CompanionWebView extends WebView implements CompanionBridge.Sink
{
    private static final String TAG = "rejuv-dual[webview]";
    public static final String UI_URL = "file:///android_asset/companion/index.html";

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final String mMode;
    private volatile boolean mReady = false;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public CompanionWebView(Context context, String mode)
    {
        super(context);
        mMode = mode;

        setBackgroundColor(Color.BLACK);
        setVerticalScrollBarEnabled(false);
        setHorizontalScrollBarEnabled(false);
        setFocusable(false);
        setFocusableInTouchMode(false);

        WebSettings ws = getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setTextZoom(100);

        setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage msg)
            {
                Log.i(TAG, "js: " + msg.message() + " (" + msg.sourceId() + ":" + msg.lineNumber() + ")");
                return true;
            }
        });

        addJavascriptInterface(new Native(), "RDNative");
        loadUrl(UI_URL + "?mode=" + mode);
    }

    public String getMode()
    {
        return mMode;
    }

    @Override
    public void deliver(final String json)
    {
        // json is ASCII-only JSON produced by the Ruby mod, safe to inline as a JS literal.
        mMain.post(() -> {
            if (!mReady)
                return;
            evaluateJavascript("window.rd && window.rd.recv(" + json + ")", null);
        });
    }

    private final class Native
    {
        @JavascriptInterface
        public void send(String json)
        {
            CompanionBridge.fromUi(json);
        }

        @JavascriptInterface
        public void ready()
        {
            mReady = true;
            // Re-attach so the latest state is replayed now that JS is listening.
            mMain.post(() -> CompanionBridge.setSink(CompanionWebView.this));
        }

        @JavascriptInterface
        public String logTail(int lines)
        {
            return LogCapture.tail(Math.max(1, Math.min(lines, 400)));
        }

        @JavascriptInterface
        public String logPath()
        {
            java.io.File f = LogCapture.getFile();
            return f == null ? "" : f.getAbsolutePath();
        }

        @JavascriptInterface
        public String remapList()
        {
            return KeyRemap.toJson();
        }

        @JavascriptInterface
        public void remapCapture()
        {
            KeyRemap.startCapture();
        }

        @JavascriptInterface
        public void remapCancel()
        {
            KeyRemap.cancelCapture();
        }

        @JavascriptInterface
        public void remapSet(int code, int target)
        {
            KeyRemap.set(code, target);
        }

        @JavascriptInterface
        public void remapClear(int code)
        {
            KeyRemap.clear(code);
        }

        @JavascriptInterface
        public void remapReset()
        {
            KeyRemap.reset();
        }

        @JavascriptInterface
        public String mode()
        {
            return mMode;
        }

        @JavascriptInterface
        public void haptic(int ms)
        {
            try {
                Vibrator vib = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
                if (vib == null || !vib.hasVibrator())
                    return;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    vib.vibrate(VibrationEffect.createOneShot(Math.max(1, ms), VibrationEffect.DEFAULT_AMPLITUDE));
                else
                    vib.vibrate(ms);
            } catch (Exception e) {
                Log.w(TAG, "haptic failed: " + e);
            }
        }
    }
}
