package com.hatkid.mkxpz.companion;

import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.Writer;
import java.util.ArrayDeque;

/**
 * Captures this app's own logcat output (Java, native and Ruby) so it can be
 * shown on the companion screen and saved to a file, without needing adb.
 * Reading your own process's log needs no permission.
 */
public final class LogCapture
{
    private static final String TAG = "rejuv-dual[log]";
    private static final int MAX_LINES = 400;

    private static final ArrayDeque<String> sLines = new ArrayDeque<>();
    private static volatile boolean sStarted = false;
    private static volatile File sFile;
    private static Writer sWriter;

    private LogCapture() {}

    public static synchronized void start()
    {
        if (sStarted)
            return;
        sStarted = true;

        Thread t = new Thread(() -> {
            java.lang.Process proc = null;
            try {
                proc = Runtime.getRuntime().exec(new String[] {
                    "logcat", "-v", "time", "--pid=" + Process.myPid()
                });
                BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                String line;
                while ((line = r.readLine()) != null)
                    add(line);
            } catch (Exception e) {
                add("log capture failed: " + e);
            } finally {
                if (proc != null)
                    proc.destroy();
            }
        }, "rejuv-dual-log");
        t.setDaemon(true);
        t.start();
    }

    /** Start mirroring the log to a file (previous lines are written too). */
    public static synchronized void setFile(File file)
    {
        if (file == null || file.equals(sFile))
            return;
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory())
                dir.mkdirs();
            Writer w = new FileWriter(file, false);
            for (String l : sLines)
                w.write(l + "\n");
            w.flush();
            if (sWriter != null)
                sWriter.close();
            sWriter = w;
            sFile = file;
            Log.i(TAG, "Writing log to " + file);
        } catch (Exception e) {
            Log.w(TAG, "Can't write log file " + file + ": " + e);
        }
    }

    public static File getFile()
    {
        return sFile;
    }

    private static synchronized void add(String line)
    {
        sLines.addLast(line);
        while (sLines.size() > MAX_LINES)
            sLines.removeFirst();
        if (sWriter != null) {
            try {
                sWriter.write(line + "\n");
                sWriter.flush();
            } catch (Exception e) {
                sWriter = null;
            }
        }
    }

    /** Last {@code n} lines joined with newlines. */
    public static synchronized String tail(int n)
    {
        StringBuilder sb = new StringBuilder();
        int skip = Math.max(0, sLines.size() - n);
        int i = 0;
        for (String l : sLines) {
            if (i++ < skip)
                continue;
            sb.append(l).append('\n');
        }
        return sb.toString();
    }
}
