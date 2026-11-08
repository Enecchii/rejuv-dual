package com.hatkid.mkxpz.companion;

import android.util.Log;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-safe message bus between the Ruby game thread and the companion UI.
 *
 * Outbound (game -> UI): Ruby calls Companion.push(json) on the SDL thread,
 * which lands in {@link #fromGame(String)}; the host forwards it to the WebView
 * on the main thread.
 *
 * Inbound (UI -> game): the WebView calls rd.send(json) from JavaScript, which
 * queues it here; Ruby drains it with Companion.poll once per frame.
 *
 * All payloads are JSON strings.
 */
public final class CompanionBridge
{
    private static final String TAG = "rejuv-dual[bridge]";
    private static final int MAX_INBOUND = 64;

    public interface Sink
    {
        void deliver(String json);
    }

    private static final ConcurrentLinkedQueue<String> sInbound = new ConcurrentLinkedQueue<>();
    private static volatile Sink sSink;
    private static volatile String sLastState;

    private CompanionBridge() {}

    /** Called by the host when a UI surface is attached (or null when detached). */
    public static void setSink(Sink sink)
    {
        sSink = sink;
        // Replay the latest state so a freshly attached UI is not blank.
        if (sink != null && sLastState != null)
            sink.deliver(sLastState);
    }

    public static boolean hasSink()
    {
        return sSink != null;
    }

    /** Game -> UI. Called from the SDL/Ruby thread. */
    public static void fromGame(String json)
    {
        if (json == null)
            return;
        if (json.startsWith("{\"t\":\"state\""))
            sLastState = json;
        Sink sink = sSink;
        if (sink != null)
            sink.deliver(json);
    }

    /** UI -> game. Called from the WebView's JS thread. */
    public static void fromUi(String json)
    {
        if (json == null)
            return;
        if (sInbound.size() >= MAX_INBOUND) {
            Log.w(TAG, "Inbound queue full, dropping oldest command");
            sInbound.poll();
        }
        sInbound.add(json);
    }

    /** Drained by Ruby once per frame. Returns null when empty. */
    public static String poll()
    {
        return sInbound.poll();
    }
}
