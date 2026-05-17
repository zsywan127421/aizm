package com.yourname.aiaimassist;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.yourname.aiaimassist.ui.FloatingWindowService;
import com.yourname.aiaimassist.core.AimAssistService;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity implements Shizuku.OnRequestPermissionResultListener {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_CODE_PERMISSIONS = 1002;
    private static final int SHIZUKU_REQUEST_CODE = 1003;
    public static final int REQUEST_CODE_SCREEN_CAPTURE = 1004;

    private TextView mStatusText;
    private Button mBtnShizuku;
    private Button mBtnFloating;
    private Button mBtnSettings;
    private boolean mShizukuAuthorized = false;
    private boolean mFloatingRunning = false;
    private android.os.Handler mHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);
            Log.i(TAG, "onCreate start");

            initViews();
            checkRuntimePermissions();
            checkShizukuPermission();

            handleRecordingCallback();

            Log.i(TAG, "onCreate end");
        } catch (Exception e) {
            Log.e(TAG, "onCreate crash: " + Log.getStackTraceString(e));
            Toast.makeText(this, "初始化失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.i(TAG, "onNewIntent: " + (intent != null ? intent.getAction() : "null"));
        setIntent(intent);
        handleRecordingCallback();
    }

    private void handleRecordingCallback() {
        Intent intent = getIntent();
        if (intent != null && "RECORDING_CALLBACK".equals(intent.getAction())) {
            Log.i(TAG, "收到录屏回调请求，准备启动录屏授权");
            mHandler.postDelayed(() -> {
                Log.i(TAG, "开始启动录屏授权");
                startScreenCapture();
            }, 300);
        }
    }

    private void initViews() {
        mStatusText = findViewById(R.id.tv_status);
        mBtnShizuku = findViewById(R.id.btn_shizuku);
        mBtnFloating = findViewById(R.id.btn_floating);
        mBtnSettings = findViewById(R.id.btn_settings);

        if (mBtnShizuku != null) mBtnShizuku.setOnClickListener(v -> requestShizukuPermission());
        if (mBtnFloating != null) mBtnFloating.setOnClickListener(v -> toggleFloatingWindow());
        if (mBtnSettings != null) mBtnSettings.setOnClickListener(v -> openSettings());

        updateStatus();
    }

    private void openSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "openSettings error", e);
        }
    }

    private void updateStatus() {
        if (mStatusText != null) {
            String overlayStatus = Settings.canDrawOverlays(this) ? "悬浮窗权限: 已授予" : "悬浮窗权限: 未授予";
            String shizukuStatus = mShizukuAuthorized ? "Shizuku: 已授权" : "Shizuku: 未授权";
            String floatStatus = mFloatingRunning ? "状态: 运行中" : "状态: 未启动";
            mStatusText.setText(overlayStatus + "\n" + shizukuStatus + "\n" + floatStatus);
        }
    }

    private void toggleFloatingWindow() {
        if (mFloatingRunning) {
            try {
                stopService(new Intent(this, FloatingWindowService.class));
                stopService(new Intent(this, AimAssistService.class));
                mFloatingRunning = false;
                if (mBtnFloating != null) mBtnFloating.setText("显示悬浮窗");
                Toast.makeText(this, "悬浮窗已关闭", Toast.LENGTH_SHORT).show();
                Log.i(TAG, "悬浮窗服务已停止");
            } catch (Exception e) {
                Log.e(TAG, "stop floating error", e);
            }
        } else {
            if (!Settings.canDrawOverlays(this)) {
                Log.w(TAG, "悬浮窗权限未授予，正在引导用户授权");
                Toast.makeText(this, "需要悬浮窗权限，正在打开设置...", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                return;
            }

            try {
                Intent serviceIntent = new Intent(this, FloatingWindowService.class);
                startService(serviceIntent);
                mFloatingRunning = true;
                if (mBtnFloating != null) mBtnFloating.setText("隐藏悬浮窗");
                Toast.makeText(this, "悬浮窗已启动\n点击悬浮球打开控制面板", Toast.LENGTH_LONG).show();
                Log.i(TAG, "悬浮窗服务已启动");
            } catch (Exception e) {
                Log.e(TAG, "start floating error", e);
                Toast.makeText(this, "启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                mFloatingRunning = false;
            }
        }
        updateStatus();
    }

    public void startScreenCapture() {
        try {
            Log.i(TAG, "准备启动录屏授权");
            android.media.projection.MediaProjectionManager projectionManager =
                (android.media.projection.MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (projectionManager == null) {
                Log.e(TAG, "MediaProjectionManager 为空");
                Toast.makeText(this, "无法获取录屏服务", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent captureIntent = projectionManager.createScreenCaptureIntent();
            startActivityForResult(captureIntent, REQUEST_CODE_SCREEN_CAPTURE);
            Log.i(TAG, "录屏授权请求已发送");
        } catch (Exception e) {
            Log.e(TAG, "startScreenCapture error", e);
            Toast.makeText(this, "录屏启动失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void checkRuntimePermissions() {
        try {
            String[] permissions;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions = new String[]{
                        Manifest.permission.FOREGROUND_SERVICE,
                        Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION,
                        Manifest.permission.POST_NOTIFICATIONS
                };
            } else {
                permissions = new String[]{
                        Manifest.permission.FOREGROUND_SERVICE
                };
            }

            boolean needRequest = false;
            for (String perm : permissions) {
                if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                    needRequest = true;
                    break;
                }
            }
            if (needRequest) {
                Log.i(TAG, "需要请求运行时权限");
                ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE_PERMISSIONS);
            } else {
                Log.i(TAG, "所有运行时权限已授予");
            }
        } catch (Exception e) {
            Log.e(TAG, "checkRuntimePermissions error", e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            boolean allGranted = true;
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    Log.w(TAG, "权限未授予: " + permissions[i]);
                }
            }
            if (!allGranted) {
                Toast.makeText(this, "部分权限未授予，可能影响功能", Toast.LENGTH_LONG).show();
            } else {
                Log.i(TAG, "所有运行时权限已授予");
            }
        }
    }

    private void checkShizukuPermission() {
        try {
            if (Shizuku.pingBinder()) {
                int result = Shizuku.checkSelfPermission();
                if (result == PackageManager.PERMISSION_GRANTED) {
                    mShizukuAuthorized = true;
                    if (mBtnShizuku != null) {
                        mBtnShizuku.setText("Shizuku: 已授权");
                        mBtnShizuku.setEnabled(false);
                    }
                } else {
                    mShizukuAuthorized = false;
                    if (mBtnShizuku != null) mBtnShizuku.setText("Shizuku: 点击授权");
                }
            } else {
                mShizukuAuthorized = false;
                if (mBtnShizuku != null) mBtnShizuku.setText("Shizuku: 未运行");
            }
        } catch (Exception e) {
            mShizukuAuthorized = false;
            if (mBtnShizuku != null) mBtnShizuku.setText("Shizuku: 错误");
        }
        updateStatus();
    }

    private void requestShizukuPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "Shizuku 服务未运行\n请先通过 ADB 激活 Shizuku", Toast.LENGTH_LONG).show();
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                mShizukuAuthorized = true;
                if (mBtnShizuku != null) {
                    mBtnShizuku.setText("Shizuku: 已授权");
                    mBtnShizuku.setEnabled(false);
                }
                updateStatus();
                return;
            }
            Shizuku.addRequestPermissionResultListener(this);
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
        } catch (Exception e) {
            Log.e(TAG, "requestShizuku error", e);
        }
    }

    @Override
    public void onRequestPermissionResult(int requestCode, int grantResult) {
        if (requestCode == SHIZUKU_REQUEST_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                mShizukuAuthorized = true;
                if (mBtnShizuku != null) {
                    mBtnShizuku.setText("Shizuku: 已授权");
                    mBtnShizuku.setEnabled(false);
                }
                Toast.makeText(this, "Shizuku 授权成功", Toast.LENGTH_SHORT).show();
            } else {
                mShizukuAuthorized = false;
                if (mBtnShizuku != null) mBtnShizuku.setText("Shizuku: 已拒绝");
                Toast.makeText(this, "Shizuku 授权被拒绝", Toast.LENGTH_SHORT).show();
            }
            updateStatus();
            try { Shizuku.removeRequestPermissionResultListener(this); } catch (Exception e) { Log.e(TAG, "removeListener error", e); }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.i(TAG, "onActivityResult: requestCode=" + requestCode + ", resultCode=" + resultCode + ", data=" + (data != null ? "not null" : "null"));
        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
            try {
                Intent serviceIntent = new Intent(this, FloatingWindowService.class);
                serviceIntent.setAction("RECORDING_RESULT");
                serviceIntent.putExtra("resultCode", resultCode);
                if (data != null) {
                    serviceIntent.putExtra("data", data);
                }
                
                startService(serviceIntent);
                Log.i(TAG, "录屏授权结果已发送到 FloatingWindowService");
            } catch (Exception e) {
                Log.e(TAG, "发送录屏结果失败", e);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkShizukuPermission();
        updateStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy");
    }
}
