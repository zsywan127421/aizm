package com.yourname.aiaimassist.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.yourname.aiaimassist.inference.QnnDetector;
import com.yourname.aiaimassist.shizuku.ShizukuShell;
import com.yourname.aiaimassist.ui.FloatingWindowService;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class AimAssistService extends Service {
    private static final String TAG = "AimAssistService";
    private static final String CHANNEL_ID = "aim_assist_channel";
    private static final int NOTIFICATION_ID = 1;

    public static final String ACTION_UPDATE_STATE = "com.yourname.aiAimAssist.UPDATE_STATE";
    public static final String ACTION_DETECTION_RESULT = "com.yourname.aiAimAssist.DETECTION_RESULT";

    private QnnDetector mDetector;
    private ExecutorService mExecutor;
    private Handler mHandler;
    private Random mRandom;

    private static final int[] MODEL_INPUT_SIZES = {256, 320, 416, 640};
    private int mModelIndex = 3;
    private int mInputSize = 640;
    private float mConfThreshold = 0.25f;
    private float mNmsThreshold = 0.45f;
    private float mDeadZoneRadius = 50f;
    private int mSwipeDuration = 50;
    private boolean mAutoAimEnabled = false;
    private int mAimIntensity = 50;
    private boolean mShowBox = false;
    private boolean mTargetTeammate = false;
    private boolean mHeadAim = true;
    private int mInferenceSkip = 2;

    private AtomicBoolean mRunning = new AtomicBoolean(false);
    private int mScreenWidth;
    private int mScreenHeight;

    private static volatile int sCurrentFps = 0;
    private static volatile int sAvgInferenceTime = 0;
    private long mFrameCount = 0;
    private long mLastFpsUpdateTime = 0;

    private BroadcastReceiver mStateReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            createNotificationChannel();
            startForeground(NOTIFICATION_ID, createNotification());

            mDetector = new QnnDetector();
            mExecutor = Executors.newSingleThreadExecutor();
            mHandler = new Handler(Looper.getMainLooper());
            mRandom = new Random();

            android.content.SharedPreferences prefs = getSharedPreferences("aim_assist_settings", MODE_PRIVATE);
            mModelIndex = prefs.getInt("model_size", 3);
            mInputSize = MODEL_INPUT_SIZES[mModelIndex];
            mConfThreshold = prefs.getFloat("conf_threshold", 0.25f);
            mNmsThreshold = prefs.getFloat("nms_threshold", 0.45f);
            mDeadZoneRadius = prefs.getFloat("dead_zone", 50f);
            mSwipeDuration = prefs.getInt("swipe_duration", 50);
            mAimIntensity = prefs.getInt("aim_intensity", 50);
            mAutoAimEnabled = prefs.getBoolean("auto_aim_enabled", false);
            mShowBox = prefs.getBoolean("show_box", false);
            mTargetTeammate = prefs.getBoolean("target_teammate", false);
            mHeadAim = prefs.getBoolean("head_aim", true);
            mInferenceSkip = prefs.getInt("inference_skip", 2);

            try {
                int initResult = mDetector.initModel();
                Log.i(TAG, "QNN model init: " + (initResult == 0 ? "success" : "failed, code=" + initResult));
            } catch (UnsatisfiedLinkError e) {
                Log.e(TAG, "Native library not found, using fallback mode");
                mDetector = null;
            } catch (Exception e) {
                Log.e(TAG, "QNN model init failed: " + Log.getStackTraceString(e));
                mDetector = null;
            }

            registerStateReceiver();
            Log.i(TAG, "AimAssistService created, model size: " + mInputSize);
        } catch (Exception e) {
            Log.e(TAG, "onCreate failed: " + Log.getStackTraceString(e));
        }
    }

    private void registerStateReceiver() {
        try {
            mStateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        if (ACTION_UPDATE_STATE.equals(intent.getAction())) {
                            if (intent.hasExtra("auto_aim_enabled")) {
                                mAutoAimEnabled = intent.getBooleanExtra("auto_aim_enabled", mAutoAimEnabled);
                            }
                            if (intent.hasExtra("dead_zone")) {
                                mDeadZoneRadius = intent.getIntExtra("dead_zone", (int) mDeadZoneRadius);
                            }
                            if (intent.hasExtra("swipe_duration")) {
                                mSwipeDuration = intent.getIntExtra("swipe_duration", mSwipeDuration);
                            }
                            if (intent.hasExtra("aim_intensity")) {
                                mAimIntensity = intent.getIntExtra("aim_intensity", mAimIntensity);
                            }
                            if (intent.hasExtra("show_box")) {
                                mShowBox = intent.getBooleanExtra("show_box", mShowBox);
                            }
                            if (intent.hasExtra("target_teammate")) {
                                mTargetTeammate = intent.getBooleanExtra("target_teammate", mTargetTeammate);
                            }
                            if (intent.hasExtra("head_aim")) {
                                mHeadAim = intent.getBooleanExtra("head_aim", mHeadAim);
                            }
                            Log.i(TAG, "State updated: aim=" + mAutoAimEnabled + ", deadzone=" + mDeadZoneRadius +
                                  ", swipe=" + mSwipeDuration + ", intensity=" + mAimIntensity +
                                  ", head=" + mHeadAim + ", box=" + mShowBox);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Receive broadcast failed: " + e.getMessage());
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(mStateReceiver, new IntentFilter(ACTION_UPDATE_STATE), Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(mStateReceiver, new IntentFilter(ACTION_UPDATE_STATE));
            }
            Log.i(TAG, "State receiver registered");
        } catch (Exception e) {
            Log.e(TAG, "Register state receiver failed: " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand: action=" + (intent != null ? intent.getAction() : "null"));

        if (intent != null && "START_RECORDING".equals(intent.getAction())) {
            mScreenWidth = intent.getIntExtra("width", 1080);
            mScreenHeight = intent.getIntExtra("height", 2340);
            mRunning.set(true);
            try {
                if (mExecutor != null && !mExecutor.isShutdown()) {
                    mExecutor.execute(this::aimAssistLoop);
                    Log.i(TAG, "Inference loop started, resolution: " + mScreenWidth + "x" + mScreenHeight);
                }
            } catch (Exception e) {
                Log.e(TAG, "Start inference loop failed: " + e.getMessage());
                mRunning.set(false);
            }
        }

        return START_STICKY;
    }

    private void aimAssistLoop() {
        Log.i(TAG, "Aim assist loop started");
        int frameCount = 0;
        int detectionSkip = 0;

        while (mRunning.get()) {
            try {
                long loopStartTime = System.currentTimeMillis();

                byte[] frameData = FloatingWindowService.getLatestFrame();

                if (frameData == null) {
                    Thread.sleep(16);
                    continue;
                }

                frameCount++;
                detectionSkip++;

                if (frameCount % mInferenceSkip != 0) {
                    continue;
                }

                float[] detections = null;
                long inferenceStart = System.currentTimeMillis();
                try {
                    if (mDetector != null) {
                        detections = mDetector.detectObjects(
                                frameData,
                                mScreenWidth,
                                mScreenHeight,
                                mInputSize,
                                mConfThreshold,
                                mNmsThreshold
                        );
                    } else {
                        Log.d(TAG, "Native detector not available, skipping inference");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Inference failed: " + e.getMessage());
                    detections = null;
                }
                long inferenceEnd = System.currentTimeMillis();
                long inferenceTime = inferenceEnd - inferenceStart;
                sAvgInferenceTime = (int) inferenceTime;

                if (detectionSkip >= 10 || detections != null) {
                    detectionSkip = 0;
                    if (detections != null && detections.length > 0) {
                        broadcastDetectionResults(detections);
                    }
                }

                updateStats(loopStartTime, inferenceEnd);

                if (!mAutoAimEnabled) {
                    continue;
                }

                if (detections == null || detections.length == 0) {
                    continue;
                }

                float bestTargetX = -1, bestTargetY = -1;
                int bestTargetHeight = 200;
                float bestConfidence = 0;
                float centerX = mScreenWidth / 2f;
                float centerY = mScreenHeight / 2f;
                float minDistance = Float.MAX_VALUE;

                for (int i = 0; i < detections.length; i += 6) {
                    if (i + 5 >= detections.length) break;

                    float x1 = detections[i];
                    float y1 = detections[i + 1];
                    float x2 = detections[i + 2];
                    float y2 = detections[i + 3];
                    float confidence = detections[i + 4];
                    int isTeammate = (int) detections[i + 5];

                    if (isTeammate == 1 && !mTargetTeammate) {
                        continue;
                    }

                    float targetX = (x1 + x2) / 2f;
                    float targetY = (y1 + y2) / 2f;
                    int boxHeight = (int) (y2 - y1);

                    float aimY = targetY;
                    if (mHeadAim) {
                        aimY = targetY - (boxHeight * 0.35f);
                    }

                    float offsetX = targetX - centerX;
                    float offsetY = aimY - centerY;
                    float distance = (float) Math.sqrt(offsetX * offsetX + offsetY * offsetY);

                    if (distance < minDistance) {
                        minDistance = distance;
                        bestConfidence = confidence;
                        bestTargetX = targetX;
                        bestTargetY = aimY;
                        bestTargetHeight = boxHeight;
                    }
                }

                if (bestTargetX < 0) {
                    continue;
                }

                float offsetX = bestTargetX - centerX;
                float offsetY = bestTargetY - centerY;

                float distance = (float) Math.sqrt(offsetX * offsetX + offsetY * offsetY);
                if (distance > mDeadZoneRadius) {
                    float intensityFactor = mAimIntensity / 100f;

                    int targetScreenX = (int) (centerX + offsetX * intensityFactor);
                    int targetScreenY = (int) (centerY + offsetY * intensityFactor);

                    targetScreenX = Math.max(0, Math.min(mScreenWidth - 1, targetScreenX));
                    targetScreenY = Math.max(0, Math.min(mScreenHeight - 1, targetScreenY));

                    ShizukuShell.swipe(
                            (int) centerX, (int) centerY,
                            targetScreenX, targetScreenY,
                            mSwipeDuration
                    );

                    Log.d(TAG, "Aim adjustment: (" + targetScreenX + "," + targetScreenY + ") confidence=" + String.format("%.2f", bestConfidence));
                }

                Thread.sleep(10 + mRandom.nextInt(20));

            } catch (InterruptedException e) {
                Log.e(TAG, "Loop interrupted");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Log.e(TAG, "Loop error: " + e.getMessage());
                try { Thread.sleep(100); } catch (InterruptedException ie) { break; }
            }
        }

        Log.i(TAG, "Aim assist loop stopped");
    }

    private void broadcastDetectionResults(float[] detections) {
        try {
            int count = Math.min(detections.length / 6, 10);
            Intent detectionIntent = new Intent(ACTION_DETECTION_RESULT);
            detectionIntent.putExtra("detection_count", count);

            for (int i = 0; i < count; i++) {
                String prefix = "det_" + i + "_";
                int idx = i * 6;

                if (idx + 5 >= detections.length) break;

                int x1 = Math.max(0, (int) detections[idx]);
                int y1 = Math.max(0, (int) detections[idx + 1]);
                int x2 = Math.min(mScreenWidth, (int) detections[idx + 2]);
                int y2 = Math.min(mScreenHeight, (int) detections[idx + 3]);
                float confidence = detections[idx + 4];
                boolean isTeammate = ((int) detections[idx + 5]) == 1;

                if (isTeammate && !mTargetTeammate) {
                    continue;
                }

                detectionIntent.putExtra(prefix + "x1", x1);
                detectionIntent.putExtra(prefix + "y1", y1);
                detectionIntent.putExtra(prefix + "x2", x2);
                detectionIntent.putExtra(prefix + "y2", y2);
                detectionIntent.putExtra(prefix + "conf", confidence);
                detectionIntent.putExtra(prefix + "teammate", isTeammate);
            }
            sendBroadcast(detectionIntent);
        } catch (Exception e) {
            Log.e(TAG, "Broadcast detection failed: " + e.getMessage());
        }
    }

    private void updateStats(long startTime, long endTime) {
        long now = System.currentTimeMillis();
        mFrameCount++;

        if (now - mLastFpsUpdateTime >= 1000) {
            sCurrentFps = (int) (mFrameCount * 1000 / (now - mLastFpsUpdateTime));
            mFrameCount = 0;
            mLastFpsUpdateTime = now;
        }

        sAvgInferenceTime = (int) (endTime - startTime);
    }

    public static int getCurrentFps() {
        return sCurrentFps;
    }

    public static int getAvgInferenceTime() {
        return sAvgInferenceTime;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "AI Aim Assist",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("AI aim assist running");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("AI Aim Assist")
                .setContentText("Running...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy");
        mRunning.set(false);
        try {
            if (mExecutor != null && !mExecutor.isShutdown()) {
                mExecutor.shutdownNow();
            }
        } catch (Exception e) {
            Log.e(TAG, "Shutdown executor failed: " + e.getMessage());
        }
        try {
            if (mStateReceiver != null) {
                unregisterReceiver(mStateReceiver);
            }
        } catch (Exception ignored) {
            Log.w(TAG, "Unregister receiver failed");
        }
        try {
            if (mDetector != null) {
                mDetector.releaseModel();
            }
        } catch (Exception e) {
            Log.e(TAG, "Release model failed: " + e.getMessage());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
