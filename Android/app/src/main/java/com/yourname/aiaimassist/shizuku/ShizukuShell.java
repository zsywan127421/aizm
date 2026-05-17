package com.yourname.aiaimassist.shizuku;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class ShizukuShell {
    private static final String TAG = "ShizukuShell";

    public static String execCommand(String cmd) {
        Process process = null;
        BufferedReader reader = null;
        try {
            process = new ProcessBuilder()
                    .command("sh", "-c", cmd)
                    .redirectErrorStream(true)
                    .start();

            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                Log.w(TAG, "Command exit code=" + exitCode + ": " + cmd);
            }
            return output.toString().trim();
        } catch (Exception e) {
            Log.e(TAG, "execCommand failed: " + e.getMessage());
            return null;
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
            if (process != null) process.destroy();
        }
    }

    public static void tap(int x, int y) {
        String cmd = "input tap " + x + " " + y;
        execCommand(cmd);
        Log.d(TAG, "Tap executed: " + x + "," + y);
    }

    public static void swipe(int x1, int y1, int x2, int y2, int duration) {
        String cmd = "input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + duration;
        execCommand(cmd);
        Log.d(TAG, "Swipe executed: " + x1 + "," + y1 + " -> " + x2 + "," + y2);
    }
}
