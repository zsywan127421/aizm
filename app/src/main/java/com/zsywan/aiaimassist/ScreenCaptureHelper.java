
package com.zsywan.aiaimassist;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Display;
import android.view.WindowManager;
import java.nio.ByteBuffer;

public class ScreenCaptureHelper {
    private Context context;
    private ImageReader imageReader;
    private MediaProjection mediaProjection;
    private HandlerThread handlerThread;
    private Handler handler;
    private int width, height;
    private byte[] latestFrame;
    
    public ScreenCaptureHelper(Context context) {
        this.context = context;
    }
    
    public void startCapture(MediaProjection projection) {
        this.mediaProjection = projection;
        
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = windowManager.getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        width = size.x;
        height = size.y;
        
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        
        handlerThread = new HandlerThread("ScreenCapture");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
        
        mediaProjection.createVirtualDisplay(
            "ScreenCapture",
            width, height,
            display.getRefreshRate(),
            0,
            imageReader.getSurface(),
            null,
            handler
        );
        
        imageReader.setOnImageAvailableListener(reader -&gt; {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image != null) {
                    latestFrame = imageToByteArray(image);
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (image != null) {
                    image.close();
                }
            }
        }, handler);
    }
    
    private byte[] imageToByteArray(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * width;
        
        byte[] data = new byte[width * height * 4];
        int offset = 0;
        
        for (int y = 0; y &lt; height; y++) {
            for (int x = 0; x &lt; width; x++) {
                data[offset] = buffer.get();
                data[offset + 1] = buffer.get();
                data[offset + 2] = buffer.get();
                data[offset + 3] = buffer.get();
                offset += 4;
            }
            buffer.position(buffer.position() + rowPadding);
        }
        
        return data;
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
        if (imageReader != null) {
            imageReader.close();
        }
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
        }
    }
}

