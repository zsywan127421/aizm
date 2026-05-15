
package com.zsywan.aiaimassist;

import android.content.Context;
import android.media.projection.MediaProjection;

public class ScreenCaptureHelper {
    private Context context;
    private int width = 1080;
    private int height = 1920;
    private byte[] latestFrame = null;
    
    public ScreenCaptureHelper(Context context) {
        this.context = context;
    }
    
    public void startCapture(MediaProjection projection) {
        // 暂时简化实现，后续完善屏幕捕获功能
    }
    
    public byte[] getLatestFrame() {
        return latestFrame;
    }
    
    public int getWidth() {
        return width;
    }
    
    public int getHeight() {
        return height;
    }
    
    public void stopCapture() {
        // 释放资源
    }
}

