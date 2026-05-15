
package com.zsywan.aiaimassist;

import android.Manifest;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_MEDIA_PROJECTION = 1001;
    private static final int REQUEST_OVERLAY_PERMISSION = 1002;
    
    private Button btnAuthorizeShizuku;
    private Button btnStartCapture;
    private Button btnStartAim;
    private Button btnStopAim;
    private TextView tvStatus;
    
    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection mediaProjection;
    private ScreenCaptureHelper screenCaptureHelper;
    private AimThread aimThread;
    
    private boolean shizukuAuthorized = false;
    private boolean captureStarted = false;
    private boolean aiming = false;
    
    static {
        System.loadLibrary("native-lib");
    }
    
    public native float[] detectObjects(byte[] rgbData, int width, int height);
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initViews();
        initServices();
    }
    
    private void initViews() {
        btnAuthorizeShizuku = findViewById(R.id.btn_authorize_shizuku);
        btnStartCapture = findViewById(R.id.btn_start_capture);
        btnStartAim = findViewById(R.id.btn_start_aim);
        btnStopAim = findViewById(R.id.btn_stop_aim);
        tvStatus = findViewById(R.id.tv_status);
        
        btnAuthorizeShizuku.setOnClickListener(v -&gt; authorizeShizuku());
        btnStartCapture.setOnClickListener(v -&gt; startCapture());
        btnStartAim.setOnClickListener(v -&gt; startAim());
        btnStopAim.setOnClickListener(v -&gt; stopAim());
    }
    
    private void initServices() {
        mediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        screenCaptureHelper = new ScreenCaptureHelper(this);
    }
    
    private void authorizeShizuku() {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, R.string.shizuku_not_installed, Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (Shizuku.checkSelfPermission() == 0) {
            shizukuAuthorized = true;
            updateStatus("Shizuku 已授权");
            Toast.makeText(this, "Shizuku 已授权", Toast.LENGTH_SHORT).show();
        } else {
            Shizuku.requestPermission(0);
        }
    }
    
    private void startCapture() {
        if (!shizukuAuthorized) {
            Toast.makeText(this, R.string.shizuku_not_authorized, Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
            return;
        }
        
        Intent permissionIntent = mediaProjectionManager.createScreenCaptureIntent();
        startActivityForResult(permissionIntent, REQUEST_MEDIA_PROJECTION);
    }
    
    private void startAim() {
        if (!captureStarted) {
            Toast.makeText(this, R.string.capture_not_started, Toast.LENGTH_SHORT).show();
            return;
        }
        
        aiming = true;
        aimThread = new AimThread();
        aimThread.start();
        
        btnStartAim.setEnabled(false);
        btnStopAim.setEnabled(true);
        updateStatus(R.string.aiming_started);
        Toast.makeText(this, R.string.aiming_started, Toast.LENGTH_SHORT).show();
    }
    
    private void stopAim() {
        aiming = false;
        if (aimThread != null) {
            aimThread.interrupt();
            aimThread = null;
        }
        
        btnStartAim.setEnabled(true);
        btnStopAim.setEnabled(false);
        updateStatus(R.string.aiming_stopped);
        Toast.makeText(this, R.string.aiming_stopped, Toast.LENGTH_SHORT).show();
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_MEDIA_PROJECTION &amp;&amp; resultCode == RESULT_OK) {
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data);
            screenCaptureHelper.startCapture(mediaProjection);
            captureStarted = true;
            btnStartAim.setEnabled(true);
            updateStatus("屏幕捕获已启动");
            Toast.makeText(this, "屏幕捕获已启动", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void updateStatus(String text) {
        tvStatus.setText("状态：" + text);
    }
    
    private void updateStatus(int resId) {
        tvStatus.setText("状态：" + getString(resId));
    }
    
    private class AimThread extends Thread {
        @Override
        public void run() {
            Handler mainHandler = new Handler(Looper.getMainLooper());
            
            while (!isInterrupted() &amp;&amp; aiming) {
                try {
                    byte[] frameData = screenCaptureHelper.getLatestFrame();
                    if (frameData != null) {
                        int width = screenCaptureHelper.getWidth();
                        int height = screenCaptureHelper.getHeight();
                        
                        float[] result = detectObjects(frameData, width, height);
                        
                        if (result != null &amp;&amp; result.length &gt;= 2) {
                            float targetX = result[0];
                            float targetY = result[1];
                            
                            int centerX = width / 2;
                            int centerY = height / 2;
                            
                            int dx = (int) (targetX - centerX);
                            int dy = (int) (targetY - centerY);
                            
                            int deadZone = 50;
                            if (Math.abs(dx) + Math.abs(dy) &gt; deadZone) {
                                String command = String.format("input swipe %d %d %d %d 10",
                                    centerX, centerY, centerX - dx, centerY - dy);
                                
                                ShizukuShell.execCommand(command);
                            }
                        }
                    }
                    
                    Thread.sleep(20);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAim();
        if (screenCaptureHelper != null) {
            screenCaptureHelper.stopCapture();
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
        }
    }
}

