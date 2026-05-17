package com.yourname.aiaimassist.screen;

import android.media.projection.MediaProjection;
import android.util.Log;

public class ScreenCapture {
    private static final String TAG = "ScreenCapture";

    public void start(MediaProjection projection, int width, int height, int dpi) {
        Log.d(TAG, "Screen capture start: " + width + "x" + height);
    }

    public void stop() {
        Log.d(TAG, "Screen capture stop");
    }
}
