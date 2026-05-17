package com.yourname.aiaimassist.inference;

import android.util.Log;

public class QnnDetector {
    private static final String TAG = "QnnDetector";

    static {
        try {
            System.loadLibrary("aimassist");
            System.loadLibrary("model_qnn_htp");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Native libraries not found: " + e.getMessage());
        }
    }

    public native int initModel();
    public native int releaseModel();
    public native float[] detectObjects(byte[] rgbData, int width, int height, int inputSize, float confThreshold, float nmsThreshold);
}
