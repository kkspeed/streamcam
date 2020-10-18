package com.github.kkspeed;

import android.view.WindowManager.LayoutParams;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.view.Surface;
import android.view.TextureView;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class MainActivity extends AppCompatActivity {
  private static final String TAG = "StreamCam";

  private TextureView mTextureView;
  private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
      openCamera();
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
      return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
    }
  };

  private final CameraCaptureSession.CaptureCallback mCaptureCallback =
      new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
            @NonNull CaptureRequest request,
            @NonNull CaptureResult partialResult) {
          super.onCaptureProgressed(session, request, partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
            @NonNull CaptureRequest request,
            @NonNull TotalCaptureResult result) {
          super.onCaptureCompleted(session, request, result);
        }
      };

  private CameraDevice mCameraDevice;
  private final CameraDevice.StateCallback mCameraStateCallback =
      new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice camera) {
          mCameraDevice = camera;
          createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
          camera.close();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
          camera.close();
        }
      };

  private Handler mBackgroundHandler;
  private Handler mImageHandler;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    startBackgroundThreads();
    getWindow().addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON);
    mTextureView = (TextureView) findViewById(R.id.camera_preview_surface);
    if (mTextureView.isAvailable()) {
      openCamera();
    } else {
      mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
    }
  }

  private static byte[] sJpegFrame;
  private final static ReadWriteLock sJpegFrameLock = new ReentrantReadWriteLock();

  public static byte[] getJpegFrame() {
    try {
      sJpegFrameLock.readLock().lock();
      return sJpegFrame;
    } finally {
      sJpegFrameLock.readLock().unlock();
    }
  }

  private static void setJpegFrame(byte[] jpegFrame) {
    try {
      sJpegFrameLock.writeLock().lock();
      sJpegFrame = jpegFrame;
    } finally {
      sJpegFrameLock.writeLock().unlock();
    }
  }

  private static final MjpegServer sMjpegServer = new MjpegServer();
  private static final Thread sServerThread = new Thread(sMjpegServer);

  private void startBackgroundThreads() {
    HandlerThread backgroundThread = new HandlerThread("ImageListener");
    backgroundThread.start();
    mBackgroundHandler = new Handler(backgroundThread.getLooper());

    HandlerThread imageThread = new HandlerThread("ImageThread");
    imageThread.start();
    mImageHandler = new Handler(imageThread.getLooper());

    sServerThread.start();
  }

  @SuppressLint("MissingPermission")
  private void openCamera() {
    final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
    requirePermission(Manifest.permission.CAMERA, granted -> {
      if (!granted) {
        throw new RuntimeException("Need camera permission.");
      }
      try {
        String cameraId = manager.getCameraIdList()[0];
        manager.openCamera(cameraId, mCameraStateCallback, mBackgroundHandler);
      } catch (CameraAccessException e) {
        Log.e(TAG, "Error getting camera ID list: ", e);
      }
    });
  }

  private ImageReader mPreviewReader;

  private void createCameraPreviewSession() {
    try {
      final SurfaceTexture texture = mTextureView.getSurfaceTexture();
      assert texture != null;
      texture.setDefaultBufferSize(1920, 1080);
      final Surface surface = new Surface(texture);

      CaptureRequest.Builder previewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
      previewRequestBuilder.addTarget(surface);

      // TODO: Add recording surface:
      mPreviewReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 2);
      mPreviewReader.setOnImageAvailableListener(mOnGetPreviewListener, mBackgroundHandler);

      previewRequestBuilder.addTarget(mPreviewReader.getSurface());

      mCameraDevice.createCaptureSession(
          Arrays.asList(surface, mPreviewReader.getSurface()),
          new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(@NonNull CameraCaptureSession session) {
              if (null == mCameraDevice) {
                return;
              }
              try {
                previewRequestBuilder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                previewRequestBuilder.set(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range.create(30, 60));
                // Flash is automatically enabled when necessary.
                previewRequestBuilder.set(
                    CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

                // Finally, we start displaying the camera preview.
                CaptureRequest previewRequest = previewRequestBuilder.build();
                session.setRepeatingRequest(
                    previewRequest, mCaptureCallback, mBackgroundHandler);
              } catch (CameraAccessException e) {
                Log.e(TAG, "Camera Access Error: ", e);
              }
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            }
          },
          null);
    } catch (CameraAccessException e) {
      Log.e(TAG, "Error accessing camera: ", e);
    }
  }

  private final ImageReader.OnImageAvailableListener mOnGetPreviewListener = reader -> {
    Image image = reader.acquireLatestImage();
    if (image != null) {
      ByteBuffer buffer = image.getPlanes()[0].getBuffer();
      byte[] imageFrame = new byte[buffer.remaining()];
      buffer.get(imageFrame);
      image.close();
      setJpegFrame(imageFrame);
    }
    Log.d(TAG, "onImageAvailable: " + System.currentTimeMillis());
  };

  private void requirePermission(final String permission, ActivityResultCallback<Boolean> next) {
    ActivityResultLauncher<String> requestPermissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), next);
    if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
      next.onActivityResult(true);
    } else {
      requestPermissionLauncher.launch(permission);
    }
  }
}