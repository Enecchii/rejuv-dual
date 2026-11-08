package com.hatkid.mkxpz.companion;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

/**
 * Decides where the companion UI lives:
 *  - A secondary display (AYN Thor bottom screen, Retroid dual, etc.): a
 *    full-screen {@link Presentation} hosting the WebView.
 *  - Single screen: a slide-in side panel over the game, toggled by a small
 *    button in the top-right corner.
 *
 * Reacts to displays being added/removed at runtime.
 */
public final class CompanionHost implements DisplayManager.DisplayListener
{
    private static final String TAG = "rejuv-dual[host]";

    private final Activity mActivity;
    private final DisplayManager mDisplayManager;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private CompanionPresentation mPresentation;
    private ViewGroup mGameLayout;
    private FrameLayout mPanel;
    private CompanionWebView mPanelWebView;
    private TextView mToggle;
    private boolean mPanelOpen = false;

    public CompanionHost(Activity activity)
    {
        mActivity = activity;
        mDisplayManager = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
    }

    /** Attach to the SDL layout and pick the best surface. Main thread. */
    public void start(ViewGroup gameLayout)
    {
        mGameLayout = gameLayout;
        mDisplayManager.registerDisplayListener(this, mMain);
        refresh();
    }

    public void stop()
    {
        mDisplayManager.unregisterDisplayListener(this);
        dismissPresentation();
        removePanel();
        CompanionBridge.setSink(null);
    }

    /** True when a UI surface (second screen or panel) is attached. */
    public static boolean isActive()
    {
        return CompanionBridge.hasSink();
    }

    private Display findSecondaryDisplay()
    {
        Display[] presentation = mDisplayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        for (Display d : presentation) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY && d.isValid())
                return d;
        }
        // Some dual-screen handhelds expose the second panel without the
        // presentation category; accept any other valid display.
        Display ownDisplay = mActivity.getWindowManager().getDefaultDisplay();
        for (Display d : mDisplayManager.getDisplays()) {
            if (d.getDisplayId() != ownDisplay.getDisplayId() && d.isValid())
                return d;
        }
        return null;
    }

    private void refresh()
    {
        if (mActivity.isFinishing())
            return;

        Display secondary = findSecondaryDisplay();
        if (secondary != null) {
            if (mPresentation != null && mPresentation.getDisplay().getDisplayId() == secondary.getDisplayId())
                return;
            dismissPresentation();
            removePanel();
            showPresentation(secondary);
        } else {
            dismissPresentation();
            ensurePanel();
        }
    }

    // ── Second screen ─────────────────────────────────────────────────────

    private void showPresentation(Display display)
    {
        try {
            Log.i(TAG, "Showing companion on display " + display.getDisplayId() + " (" + display.getName() + ")");
            mPresentation = new CompanionPresentation(mActivity, display);
            mPresentation.setOnDismissListener(d -> {
                if (mPresentation != null && mPresentation == d) {
                    mPresentation = null;
                    CompanionBridge.setSink(null);
                }
            });
            mPresentation.show();
        } catch (WindowManager.InvalidDisplayException e) {
            Log.w(TAG, "Couldn't show presentation: " + e);
            mPresentation = null;
            ensurePanel();
        }
    }

    private void dismissPresentation()
    {
        if (mPresentation != null) {
            CompanionPresentation p = mPresentation;
            mPresentation = null;
            p.dismiss();
            CompanionBridge.setSink(null);
        }
    }

    private final class CompanionPresentation extends Presentation
    {
        private CompanionWebView mWebView;

        CompanionPresentation(Context outer, Display display)
        {
            super(outer, display);
        }

        @Override
        protected void onCreate(Bundle savedInstanceState)
        {
            super.onCreate(savedInstanceState);
            // Never take key focus: physical buttons must keep going to the game.
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            mWebView = new CompanionWebView(getContext(), "screen");
            setContentView(mWebView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            CompanionBridge.setSink(mWebView);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event)
        {
            // If focus ever lands here anyway, hand keys back to the game.
            return mActivity.dispatchKeyEvent(event);
        }

        @Override
        public void onDetachedFromWindow()
        {
            if (mWebView != null) {
                mWebView.destroy();
                mWebView = null;
            }
            super.onDetachedFromWindow();
        }
    }

    // ── Single-screen side panel ─────────────────────────────────────────

    private int dp(float v)
    {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                mActivity.getResources().getDisplayMetrics()));
    }

    private void ensurePanel()
    {
        if (mGameLayout == null || mPanel != null)
            return;

        int screenW = mActivity.getResources().getDisplayMetrics().widthPixels;

        mPanel = new FrameLayout(mActivity);
        mPanel.setVisibility(View.GONE);
        mPanelWebView = new CompanionWebView(mActivity, "panel");
        mPanel.addView(mPanelWebView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Keep touches on the panel from reaching the game surface / gamepad.
        mPanel.setOnTouchListener((v, e) -> true);

        RelativeLayout.LayoutParams panelLp = new RelativeLayout.LayoutParams(
                (int) (screenW * 0.42f), ViewGroup.LayoutParams.MATCH_PARENT);
        panelLp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        mGameLayout.addView(mPanel, panelLp);

        mToggle = new TextView(mActivity);
        mToggle.setText("◧");
        mToggle.setTextColor(Color.WHITE);
        mToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        mToggle.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x99000000);
        bg.setCornerRadius(dp(10));
        mToggle.setBackground(bg);
        mToggle.setAlpha(0.7f);
        mToggle.setOnClickListener(v -> setPanelOpen(!mPanelOpen));
        mToggle.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP)
                v.performClick();
            return true;
        });
        RelativeLayout.LayoutParams toggleLp = new RelativeLayout.LayoutParams(dp(40), dp(40));
        toggleLp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        toggleLp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        toggleLp.setMargins(0, dp(8), dp(8), 0);
        mGameLayout.addView(mToggle, toggleLp);

        // The panel WebView is live (receives state) even while hidden.
        CompanionBridge.setSink(mPanelWebView);
    }

    private void setPanelOpen(boolean open)
    {
        mPanelOpen = open;
        if (mPanel != null)
            mPanel.setVisibility(open ? View.VISIBLE : View.GONE);
        if (mToggle != null) {
            mToggle.bringToFront();
            mToggle.setAlpha(open ? 1f : 0.7f);
        }
    }

    private void removePanel()
    {
        if (mGameLayout != null) {
            if (mPanel != null)
                mGameLayout.removeView(mPanel);
            if (mToggle != null)
                mGameLayout.removeView(mToggle);
        }
        if (mPanelWebView != null) {
            mPanelWebView.destroy();
            mPanelWebView = null;
        }
        mPanel = null;
        mToggle = null;
        mPanelOpen = false;
    }

    // ── DisplayListener ───────────────────────────────────────────────────

    @Override
    public void onDisplayAdded(int displayId)
    {
        refresh();
    }

    @Override
    public void onDisplayRemoved(int displayId)
    {
        refresh();
    }

    @Override
    public void onDisplayChanged(int displayId)
    {
        // no-op
    }
}
