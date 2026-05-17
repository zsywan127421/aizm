package com.yourname.aiaimassist.ui;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.yourname.aiaimassist.MainActivity;
import com.yourname.aiaimassist.R;
import com.yourname.aiaimassist.core.AimAssistService;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class FloatingWindowService extends Service {
    private static final String TAG = "FloatingWindowService";
    private static final String CHANNEL_ID = "floating_window_channel";
    private static final int NOTIFICATION_ID = 2;

    public static final String ACTION_DETECTION_RESULT = "com.yourname.aiAimAssist.DETECTION_RESULT";

    private WindowManager mWindowManager;
    private View mFloatingView;
    private View mControlPanelView;
    private boolean mPanelVisible = false;

    private Button mBtnRecord;
    private Button mBtnToggleAim;
    private SeekBar mSeekDeadZone;
    private SeekBar mSeekSwipeDuration;
    private SeekBar mSeekAimIntensity;
    private SeekBar mSeekInputSize;
    private TextView mTvDeadZone;
    private TextView mTvSwipeDuration;
    private TextView mTvAimIntensity;
    private TextView mTvInputSize;
    private TextView mTvFps;
    private TextView mTvInferenceTime;
    private TextView mTvStatus;
    private CheckBox mChkShowBox;
    private CheckBox mChkTargetTeammate;
    private CheckBox mChkHeadAim;

    private DetectionOverlayView mDetectionOverlay;
    private WindowManager.LayoutParams mOverlayParams;
    private WindowManager.LayoutParams mControlPanelParams;

    private BroadcastReceiver mDetectionReceiver;

    private int mDeadZoneRadius = 50;
    private int mSwipeDuration = 50;
    private int mAimIntensity = 50;
    private volatile int mInputSizeIndex = 3;
    private boolean mAutoAimEnabled = false;
    private volatile boolean mRecording = false;
    private boolean mShowBox = false;
    private boolean mTargetTeammate = false;
    private boolean mHeadAim = true;

    private Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mUpdateStatsRunnable;

    private int mInitialX, mInitialY;
    private float mTouchStartX, mTouchStartY;
    private boolean mIsDragging = false;

    private MediaProjectionManager mProjectionManager;
    private MediaProjection mMediaProjection;
    private ImageReader mImageReader;
    private VirtualDisplay mVirtualDisplay;
    private int mScreenWidth, mScreenHeight;
    private byte[] mLastFrame;

    private Thread mCaptureThread;
    private volatile boolean mCaptureRunning = false;

    // Shared frame data for AimAssistService
    private static volatile byte[] sSharedFrame;
    private static volatile int sFrameWidth;
    private static volatile int sFrameHeight;
    private static volatile long sFrameTimestamp;

    // MediaProjection callback
    private MediaProjection.Callback mMediaProjectionCallback;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            mProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            DisplayMetrics dm = getResources().getDisplayMetrics();
            mScreenWidth = dm.widthPixels;
            mScreenHeight = dm.heightPixels;
            Log.i(TAG, "Screen resolution: " + mScreenWidth + "x" + mScreenHeight);

            createNotificationChannel();
            startForeground(NOTIFICATION_ID, createNotification());

            createFloatingBall();
            createDetectionOverlay();
            registerDetectionReceiver();
            Log.i(TAG, "FloatingWindowService onCreate success");
        } catch (Exception e) {
            Log.e(TAG, "onCreate failed: " + Log.getStackTraceString(e));
            stopSelf();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "AI Aim Assist",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Floating window running");
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
                .setContentText("Floating window running")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
    }

    private void createFloatingBall() {
        try {
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 0;
            params.y = 200;

            mFloatingView = LayoutInflater.from(this).inflate(R.layout.floating_ball, null);
            if (mFloatingView == null) {
                Log.e(TAG, "Floating ball layout load failed");
                return;
            }
            mFloatingView.setOnTouchListener(createDragListener(params));
            mFloatingView.setOnClickListener(v -> toggleControlPanel());

            mWindowManager.addView(mFloatingView, params);
            Log.i(TAG, "Floating ball created");
        } catch (Exception e) {
            Log.e(TAG, "Create floating ball failed: " + Log.getStackTraceString(e));
        }
    }

    private void createDetectionOverlay() {
        try {
            mOverlayParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
            );
            mOverlayParams.gravity = Gravity.TOP | Gravity.START;

            mDetectionOverlay = new DetectionOverlayView(this);
            mDetectionOverlay.setDetectionScale(mScreenWidth, mScreenHeight);
            mDetectionOverlay.setShowBoxes(mShowBox);
            mDetectionOverlay.setTargetTeammate(mTargetTeammate);

            mWindowManager.addView(mDetectionOverlay, mOverlayParams);
            Log.i(TAG, "Detection overlay created");
        } catch (Exception e) {
            Log.e(TAG, "Create detection overlay failed: " + e.getMessage());
            mDetectionOverlay = null;
        }
    }

    private void registerDetectionReceiver() {
        try {
            mDetectionReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent != null && ACTION_DETECTION_RESULT.equals(intent.getAction())) {
                        handleDetectionResults(intent);
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(mDetectionReceiver, new IntentFilter(ACTION_DETECTION_RESULT), Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(mDetectionReceiver, new IntentFilter(ACTION_DETECTION_RESULT));
            }
            Log.i(TAG, "Detection receiver registered");
        } catch (Exception e) {
            Log.e(TAG, "Register receiver failed: " + e.getMessage());
        }
    }

    private void handleDetectionResults(Intent intent) {
        try {
            int count = intent.getIntExtra("detection_count", 0);
            if (count <= 0) {
                if (mDetectionOverlay != null) {
                    mDetectionOverlay.clearDetections();
                }
                return;
            }

            List<DetectionOverlayView.DetectionResult> detections = new ArrayList<>();

            for (int i = 0; i < count; i++) {
                String prefix = "det_" + i + "_";
                int x1 = intent.getIntExtra(prefix + "x1", 0);
                int y1 = intent.getIntExtra(prefix + "y1", 0);
                int x2 = intent.getIntExtra(prefix + "x2", 0);
                int y2 = intent.getIntExtra(prefix + "y2", 0);
                float confidence = intent.getFloatExtra(prefix + "conf", 0f);
                boolean isTeammate = intent.getBooleanExtra(prefix + "teammate", false);

                if (x2 > x1 && y2 > y1) {
                    detections.add(new DetectionOverlayView.DetectionResult(x1, y1, x2, y2, confidence, isTeammate));
                }
            }

            if (mDetectionOverlay != null) {
                mDetectionOverlay.updateDetections(detections);
            }
        } catch (Exception e) {
            Log.e(TAG, "Handle detection failed: " + e.getMessage());
        }
    }

    private View.OnTouchListener createDragListener(WindowManager.LayoutParams params) {
        return (v, event) -> {
            try {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        mInitialX = params.x;
                        mInitialY = params.y;
                        mTouchStartX = event.getRawX();
                        mTouchStartY = event.getRawY();
                        mIsDragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int deltaX = (int) (event.getRawX() - mTouchStartX);
                        int deltaY = (int) (event.getRawY() - mTouchStartY);
                        if (Math.abs(deltaX) > 5 || Math.abs(deltaY) > 5) {
                            mIsDragging = true;
                            params.x = mInitialX + deltaX;
                            params.y = mInitialY + deltaY;
                            mWindowManager.updateViewLayout(mFloatingView, params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!mIsDragging) v.performClick();
                        return true;
                }
            } catch (Exception e) {
                Log.e(TAG, "Drag handling failed: " + e.getMessage());
            }
            return false;
        };
    }

    private void toggleControlPanel() {
        if (mPanelVisible) hideControlPanel();
        else showControlPanel();
    }

    private void showControlPanel() {
        if (mControlPanelView != null) return;

        try {
            mControlPanelView = LayoutInflater.from(this).inflate(R.layout.floating_control_panel, null);

            mTvStatus = mControlPanelView.findViewById(R.id.tv_status);
            mBtnRecord = mControlPanelView.findViewById(R.id.btn_record);
            mBtnToggleAim = mControlPanelView.findViewById(R.id.btn_toggle_aim);
            mSeekDeadZone = mControlPanelView.findViewById(R.id.seek_deadzone);
            mSeekSwipeDuration = mControlPanelView.findViewById(R.id.seek_swipe_duration);
            mSeekAimIntensity = mControlPanelView.findViewById(R.id.seek_aim_intensity);
            mSeekInputSize = mControlPanelView.findViewById(R.id.seek_input_size);
            mTvDeadZone = mControlPanelView.findViewById(R.id.tv_deadzone_value);
            mTvSwipeDuration = mControlPanelView.findViewById(R.id.tv_swipe_duration_value);
            mTvAimIntensity = mControlPanelView.findViewById(R.id.tv_aim_intensity_value);
            mTvInputSize = mControlPanelView.findViewById(R.id.tv_input_size_value);
            mTvFps = mControlPanelView.findViewById(R.id.tv_fps);
            mTvInferenceTime = mControlPanelView.findViewById(R.id.tv_inference_time);
            mChkShowBox = mControlPanelView.findViewById(R.id.chk_show_box);
            mChkTargetTeammate = mControlPanelView.findViewById(R.id.chk_target_teammate);
            mChkHeadAim = mControlPanelView.findViewById(R.id.chk_head_aim);

            if (mBtnRecord != null) mBtnRecord.setOnClickListener(v -> {
                Log.i(TAG, "Record button clicked, current: " + (mRecording ? "recording" : "not recording"));
                if (!mRecording) startRecording();
                else stopRecording();
            });

            if (mBtnToggleAim != null) mBtnToggleAim.setOnClickListener(v -> {
                mAutoAimEnabled = !mAutoAimEnabled;
                mBtnToggleAim.setText(mAutoAimEnabled ? "Auto-aim: ON" : "Auto-aim: OFF");
                updateAimState();
            });

            if (mChkShowBox != null) mChkShowBox.setOnCheckedChangeListener((b, c) -> {
                mShowBox = c;
                if (mDetectionOverlay != null) {
                    mDetectionOverlay.setShowBoxes(c);
                }
                updateSettings();
            });

            if (mChkTargetTeammate != null) mChkTargetTeammate.setOnCheckedChangeListener((b, c) -> {
                mTargetTeammate = c;
                if (mDetectionOverlay != null) {
                    mDetectionOverlay.setTargetTeammate(c);
                }
                updateSettings();
            });

            if (mChkHeadAim != null) mChkHeadAim.setOnCheckedChangeListener((b, c) -> {
                mHeadAim = c;
                updateSettings();
            });

            if (mSeekDeadZone != null) {
                mSeekDeadZone.setProgress(mDeadZoneRadius);
                mSeekDeadZone.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        mDeadZoneRadius = progress;
                        mTvDeadZone.setText(progress + "px");
                        updateSettings();
                    }
                    @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                    @Override public void onStopTrackingTouch(SeekBar seekBar) {}
                });
            }

            if (mSeekSwipeDuration != null) {
                mSeekSwipeDuration.setProgress(Math.max(0, mSwipeDuration - 5));
                mSeekSwipeDuration.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        mSwipeDuration = progress + 5;
                        mTvSwipeDuration.setText(mSwipeDuration + "ms");
                        updateSettings();
                    }
                    @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                    @Override public void onStopTrackingTouch(SeekBar seekBar) {}
                });
            }

            if (mSeekAimIntensity != null) {
                mSeekAimIntensity.setProgress(mAimIntensity);
                mSeekAimIntensity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        mAimIntensity = progress;
                        mTvAimIntensity.setText(progress + "%");
                        updateSettings();
                    }
                    @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                    @Override public void onStopTrackingTouch(SeekBar seekBar) {}
                });
            }

            String[] sizes = {"256 (Fast)", "320 (Balanced)", "416 (Standard)", "640 (HD)"};
            if (mTvInputSize != null) mTvInputSize.setText(sizes[Math.min(mInputSizeIndex, sizes.length - 1)]);
            if (mSeekInputSize != null) {
                mSeekInputSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        int safeIndex = Math.min(progress, sizes.length - 1);
                        mInputSizeIndex = safeIndex;
                        mTvInputSize.setText(sizes[safeIndex]);
                    }
                    @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                    @Override public void onStopTrackingTouch(SeekBar seekBar) {}
                });
            }

            mControlPanelParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
            );
            mControlPanelParams.gravity = Gravity.TOP | Gravity.START;
            mControlPanelParams.x = 0;
            mControlPanelParams.y = 300;

            mWindowManager.addView(mControlPanelView, mControlPanelParams);
            mPanelVisible = true;
            startUpdateStats();
            Log.i(TAG, "Control panel shown");
        } catch (Exception e) {
            Log.e(TAG, "Show control panel failed: " + Log.getStackTraceString(e));
        }
    }

    private void hideControlPanel() {
        if (mControlPanelView != null) {
            try {
                mWindowManager.removeView(mControlPanelView);
            } catch (Exception ignored) {}
            mControlPanelView = null;
            mPanelVisible = false;
            stopUpdateStats();
            Log.i(TAG, "Control panel hidden");
        }
    }

    private void startRecording() {
        try {
            Log.i(TAG, "Starting screen recording authorization...");
            Intent intent = new Intent(this, MainActivity.class);
            intent.setAction("RECORDING_CALLBACK");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            Toast.makeText(this, "Opening screen recording authorization...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Start recording failed: " + Log.getStackTraceString(e));
            Toast.makeText(this, "Recording start failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public void onRecordingGranted(int resultCode, Intent data) {
        try {
            Log.i(TAG, "onRecordingGranted: resultCode=" + resultCode + ", data=" + (data != null ? "not null" : "null"));

            if (data == null) {
                Log.e(TAG, "Recording authorization data is null");
                Toast.makeText(this, "Recording authorization data is null", Toast.LENGTH_SHORT).show();
                return;
            }

            if (mProjectionManager == null) {
                Log.e(TAG, "MediaProjectionManager is null");
                Toast.makeText(this, "Screen recording service unavailable", Toast.LENGTH_SHORT).show();
                return;
            }

            mMediaProjection = mProjectionManager.getMediaProjection(resultCode, data);
            if (mMediaProjection == null) {
                Log.e(TAG, "getMediaProjection returned null");
                Toast.makeText(this, "Recording authorization failed", Toast.LENGTH_SHORT).show();
                return;
            }
            Log.i(TAG, "MediaProjection obtained successfully");

            mMediaProjectionCallback = new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.w(TAG, "MediaProjection stopped by system");
                    mHandler.post(() -> {
                        if (mRecording) {
                            stopRecording();
                            Toast.makeText(FloatingWindowService.this, "Recording stopped", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            };
            mMediaProjection.registerCallback(mMediaProjectionCallback, mHandler);

            mImageReader = ImageReader.newInstance(mScreenWidth, mScreenHeight, PixelFormat.RGBA_8888, 2);
            if (mImageReader == null) {
                Log.e(TAG, "ImageReader creation failed");
                Toast.makeText(this, "Image reader creation failed", Toast.LENGTH_SHORT).show();
                return;
            }
            Log.i(TAG, "ImageReader created: " + mScreenWidth + "x" + mScreenHeight);

            mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                    "AI_Aim", mScreenWidth, mScreenHeight, 300,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mImageReader.getSurface(), null, null
            );
            if (mVirtualDisplay == null) {
                Log.e(TAG, "VirtualDisplay creation failed");
                Toast.makeText(this, "Virtual display creation failed", Toast.LENGTH_SHORT).show();
                return;
            }
            Log.i(TAG, "VirtualDisplay created successfully");

            mRecording = true;
            mHandler.post(() -> {
                try {
                    if (mBtnRecord != null) mBtnRecord.setText("Stop Recording");
                    if (mTvStatus != null) mTvStatus.setText("Recording");
                } catch (Exception e) {
                    Log.e(TAG, "UI update failed: " + e.getMessage());
                }
            });

            mCaptureRunning = true;
            mCaptureThread = new Thread(this::captureLoop, "CaptureThread");
            mCaptureThread.start();
            Log.i(TAG, "CaptureThread started");

            mHandler.postDelayed(() -> {
                if (mRecording && getLatestFrame() != null) {
                    Intent serviceIntent = new Intent(this, AimAssistService.class);
                    serviceIntent.setAction("START_RECORDING");
                    serviceIntent.putExtra("width", mScreenWidth);
                    serviceIntent.putExtra("height", mScreenHeight);
                    try {
                        startService(serviceIntent);
                        Log.i(TAG, "AimAssistService started (frame data ready)");
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to start AimAssistService: " + Log.getStackTraceString(e));
                    }
                } else {
                    Log.w(TAG, "Frame data not ready, retrying AimAssistService start");
                    startAimAssistServiceIfReady();
                }
            }, 1500);

            Log.i(TAG, "Recording started");
            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "onRecordingGranted error: " + Log.getStackTraceString(e));
            Toast.makeText(this, "Recording failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            mRecording = false;
            mCaptureRunning = false;
        }
    }

    private void captureLoop() {
        Log.i(TAG, "captureLoop started");
        try {
            if (mImageReader == null) {
                Log.e(TAG, "mImageReader is null, captureLoop exiting");
                return;
            }

            int maxBufferSize = mScreenWidth * mScreenHeight * 4;
            byte[] buf = new byte[Math.min(maxBufferSize, 50 * 1024 * 1024)];
            int frameCounter = 0;

            while (mCaptureRunning && !Thread.currentThread().isInterrupted()) {
                Image image = null;
                try {
                    image = mImageReader.acquireLatestImage();
                    if (image == null) {
                        Thread.sleep(16);
                        continue;
                    }

                    Image.Plane[] planes = image.getPlanes();
                    if (planes == null || planes.length == 0) {
                        image.close();
                        image = null;
                        Thread.sleep(16);
                        continue;
                    }

                    ByteBuffer buffer = planes[0].getBuffer();
                    int pixelStride = planes[0].getPixelStride();
                    int rowStride = planes[0].getRowStride();

                    if (rowStride > 0 && pixelStride > 0 && buffer.capacity() > 0) {
                        int idx = 0;
                        int requiredSize = mScreenHeight * mScreenWidth * 3;
                        if (idx + requiredSize > buf.length) {
                            buf = new byte[Math.min(requiredSize, 50 * 1024 * 1024)];
                        }

                        for (int y = 0; y < mScreenHeight; y++) {
                            int rowOffset = y * rowStride;
                            for (int x = 0; x < mScreenWidth; x++) {
                                int srcIdx = rowOffset + x * pixelStride;
                                if (srcIdx + 3 < buffer.capacity() && idx + 2 < buf.length) {
                                    buf[idx++] = buffer.get(srcIdx);
                                    buf[idx++] = buffer.get(srcIdx + 1);
                                    buf[idx++] = buffer.get(srcIdx + 2);
                                }
                            }
                        }

                        synchronized (this) {
                            if (idx > 0 && idx < buf.length) {
                                mLastFrame = new byte[idx];
                                System.arraycopy(buf, 0, mLastFrame, 0, idx);
                                byte[] frameCopy = mLastFrame.clone();
                                int width = mScreenWidth;
                                int height = mScreenHeight;
                                long timestamp = System.currentTimeMillis();
                                sSharedFrame = frameCopy;
                                sFrameWidth = width;
                                sFrameHeight = height;
                                sFrameTimestamp = timestamp;
                            }
                        }
                    }

                    image.close();
                    image = null;
                    frameCounter++;
                    if (frameCounter % 30 == 0) {
                        Log.d(TAG, "Captured " + frameCounter + " frames");
                    }
                    Thread.sleep(33);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "captureLoop error: " + e.getMessage());
                    try { Thread.sleep(100); } catch (InterruptedException ie) { break; }
                } finally {
                    if (image != null) {
                        try { image.close(); } catch (Exception ignored) {}
                    }
                }
            }

            Log.i(TAG, "captureLoop exited, captured " + frameCounter + " frames");
        } catch (Exception e) {
            Log.e(TAG, "captureLoop exception: " + Log.getStackTraceString(e));
        }
    }

    private void stopRecording() {
        try {
            Log.i(TAG, "Stopping recording...");
            mCaptureRunning = false;
            if (mCaptureThread != null && mCaptureThread.isAlive()) {
                mCaptureThread.interrupt();
                try {
                    mCaptureThread.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (mVirtualDisplay != null) {
                mVirtualDisplay.release();
                mVirtualDisplay = null;
            }
            if (mImageReader != null) {
                mImageReader.close();
                mImageReader = null;
            }
            if (mMediaProjection != null) {
                if (mMediaProjectionCallback != null) {
                    mMediaProjection.unregisterCallback(mMediaProjectionCallback);
                }
                mMediaProjection.stop();
                mMediaProjection = null;
            }
            mRecording = false;
            try { stopService(new Intent(this, AimAssistService.class)); } catch (Exception ignored) {}

            mHandler.post(() -> {
                try {
                    if (mBtnRecord != null) mBtnRecord.setText("Start Recording");
                    if (mTvStatus != null) mTvStatus.setText("Stopped");
                } catch (Exception e) {
                    Log.e(TAG, "UI update failed: " + e.getMessage());
                }
            });

            Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show();
            Log.i(TAG, "Recording stopped");
        } catch (Exception e) {
            Log.e(TAG, "stopRecording error: " + e.getMessage());
        }
    }

    private void updateAimState() {
        try {
            Intent intent = new Intent(AimAssistService.ACTION_UPDATE_STATE);
            intent.putExtra("auto_aim_enabled", mAutoAimEnabled);
            intent.putExtra("dead_zone", mDeadZoneRadius);
            intent.putExtra("swipe_duration", mSwipeDuration);
            intent.putExtra("aim_intensity", mAimIntensity);
            intent.putExtra("show_box", mShowBox);
            intent.putExtra("target_teammate", mTargetTeammate);
            intent.putExtra("head_aim", mHeadAim);
            sendBroadcast(intent);
            Log.d(TAG, "Update aim state: aim=" + mAutoAimEnabled + ", intensity=" + mAimIntensity);
        } catch (Exception e) {
            Log.e(TAG, "updateAimState error: " + e.getMessage());
        }
    }

    private void updateSettings() {
        try {
            Intent intent = new Intent(AimAssistService.ACTION_UPDATE_STATE);
            intent.putExtra("dead_zone", mDeadZoneRadius);
            intent.putExtra("swipe_duration", mSwipeDuration);
            intent.putExtra("aim_intensity", mAimIntensity);
            intent.putExtra("show_box", mShowBox);
            intent.putExtra("target_teammate", mTargetTeammate);
            intent.putExtra("head_aim", mHeadAim);
            sendBroadcast(intent);
        } catch (Exception e) {
            Log.e(TAG, "updateSettings error: " + e.getMessage());
        }
    }

    private void startUpdateStats() {
        mUpdateStatsRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    if (mTvFps != null) mTvFps.setText("FPS: " + AimAssistService.getCurrentFps() + " FPS");
                    if (mTvInferenceTime != null) mTvInferenceTime.setText("Inference: " + AimAssistService.getAvgInferenceTime() + "ms");
                } catch (Exception e) {
                    Log.e(TAG, "updateStats error: " + e.getMessage());
                }
                mHandler.postDelayed(this, 1000);
            }
        };
        mHandler.post(mUpdateStatsRunnable);
    }

    private void stopUpdateStats() {
        if (mUpdateStatsRunnable != null) mHandler.removeCallbacks(mUpdateStatsRunnable);
    }

    private void startAimAssistServiceIfReady() {
        if (!mRecording) return;
        if (getLatestFrame() != null) {
            try {
                Intent serviceIntent = new Intent(this, AimAssistService.class);
                serviceIntent.setAction("START_RECORDING");
                serviceIntent.putExtra("width", mScreenWidth);
                serviceIntent.putExtra("height", mScreenHeight);
                startService(serviceIntent);
                Log.i(TAG, "AimAssistService started (frame data ready)");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start AimAssistService: " + Log.getStackTraceString(e));
            }
        } else {
            Log.w(TAG, "Frame data not ready, retrying in 500ms");
            mHandler.postDelayed(this::startAimAssistServiceIfReady, 500);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand: " + (intent != null ? intent.getAction() : "null"));
        if (intent != null && "RECORDING_RESULT".equals(intent.getAction())) {
            int resultCode = intent.getIntExtra("resultCode", -1);
            Intent data = getParcelableExtra(intent, "data");
            Log.i(TAG, "Received recording result: resultCode=" + resultCode + ", data=" + (data != null ? "not null" : "null"));
            if (resultCode == Activity.RESULT_OK && data != null) {
                Log.i(TAG, "Recording authorization successful");
                onRecordingGranted(resultCode, data);
            } else {
                Log.w(TAG, "Recording authorization rejected or failed");
                Toast.makeText(this, "Recording authorization rejected", Toast.LENGTH_SHORT).show();
                mHandler.post(() -> {
                    if (mBtnRecord != null) mBtnRecord.setText("Start Recording");
                });
            }
        }
        return START_STICKY;
    }

    @SuppressWarnings("deprecation")
    private Intent getParcelableExtra(Intent intent, String key) {
        if (intent == null) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(key, Intent.class);
        } else {
            return intent.getParcelableExtra(key);
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    public static byte[] getLatestFrame() {
        return sSharedFrame;
    }

    public static int getFrameWidth() {
        return sFrameWidth;
    }

    public static int getFrameHeight() {
        return sFrameHeight;
    }

    public static long getFrameTimestamp() {
        return sFrameTimestamp;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy");
        try { stopUpdateStats(); } catch (Exception e) { Log.e(TAG, "Stop update stats failed", e); }
        try { stopRecording(); } catch (Exception e) { Log.e(TAG, "Stop recording failed", e); }
        try { hideControlPanel(); } catch (Exception e) { Log.e(TAG, "Hide control panel failed", e); }

        sSharedFrame = null;
        sFrameWidth = 0;
        sFrameHeight = 0;
        sFrameTimestamp = 0;

        try { if (mFloatingView != null && mWindowManager != null) mWindowManager.removeView(mFloatingView); } catch (Exception ignored) {}
        try { if (mDetectionOverlay != null && mWindowManager != null) mWindowManager.removeView(mDetectionOverlay); } catch (Exception ignored) {}
        try { if (mDetectionReceiver != null) unregisterReceiver(mDetectionReceiver); } catch (Exception ignored) {}
    }
}
