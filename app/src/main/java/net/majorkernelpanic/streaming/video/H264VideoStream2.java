package net.majorkernelpanic.streaming.video;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCaptureSession.CaptureCallback;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraDevice.StateCallback;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaCodec.Callback;
import android.media.MediaCodec.CodecException;
import android.media.MediaCodecInfo;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.MediaRecorder.OutputFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.util.Log;
import android.util.Range;
import android.view.Surface;
import androidx.annotation.NonNull;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import net.majorkernelpanic.streaming.MediaStream;
import net.majorkernelpanic.streaming.exceptions.ConfNotSupportedException;
import net.majorkernelpanic.streaming.hw.CodecManager;
import net.majorkernelpanic.streaming.hw.CodecManager.Codec;
import net.majorkernelpanic.streaming.mp4.MP4Config;
import net.majorkernelpanic.streaming.rtp.H264Packetizer;
import net.majorkernelpanic.streaming.rtp.MediaCodecInputStream;

public class H264VideoStream2 extends MediaStream {
  public static Context sContext;

  private final static String TAG = "H264VideoStream2";
  private final static String MIME_TYPE = "video/avc";
  private final static int VIDEO_ENCODER = MediaRecorder.VideoEncoder.H264;

  private Handler mBackgroundHandler;

  public H264VideoStream2() {
    mPacketizer = new H264Packetizer();
    HandlerThread backgroundThread = new HandlerThread("Camera...");
    backgroundThread.start();
    mBackgroundHandler = new Handler(backgroundThread.getLooper());
  }

  @SuppressLint("MissingPermission")
  @Override
  protected void encodeWithMediaRecorder() throws IOException {
    Log.d(TAG, "Video encoded using the MediaRecorderAPI");

    createSockets();

    try {
      mMediaRecorder = new MediaRecorder();
      mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
      mMediaRecorder.setOutputFormat(OutputFormat.MPEG_4);
      mMediaRecorder.setVideoEncoder(VIDEO_ENCODER);
      mMediaRecorder.setVideoSize(1920, 1080);
      mMediaRecorder.setVideoFrameRate(30);
      mMediaRecorder.setVideoEncodingBitRate(10000000);

      FileDescriptor fd = mParcelWrite.getFileDescriptor();
      Log.e("H264", "File descriptor: " + fd);
      mMediaRecorder.setOutputFile(fd);

      mMediaRecorder.prepare();

      final CameraManager manager = (CameraManager) sContext.getSystemService(Context.CAMERA_SERVICE);
      try {
        String cameraId = manager.getCameraIdList()[0];
        manager.openCamera(cameraId, new StateCallback() {
          @SuppressLint("NewApi")
          @Override
          public void onOpened(@NonNull CameraDevice camera) {
            try {
              CaptureRequest.Builder previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
              previewRequestBuilder.addTarget(mMediaRecorder.getSurface());
              camera.createCaptureSession(
                  Collections.singletonList(mMediaRecorder.getSurface()),
                  new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                      previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                          CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                      previewRequestBuilder.set(
                          CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                          Range.create(30, 30));
                      CaptureRequest previewRequest = previewRequestBuilder.build();
                      try {
                        mMediaRecorder.start();
                        session.setRepeatingRequest(previewRequest, new CameraCaptureSession.CaptureCallback() {
                          @Override
                          public void onCaptureStarted(@NonNull CameraCaptureSession session,
                              @NonNull CaptureRequest request, long timestamp, long frameNumber) {
                            super.onCaptureStarted(session, request, timestamp, frameNumber);
                            Log.e(TAG, "Capture started...");
                          }

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
                        }, mBackgroundHandler);
                      } catch (CameraAccessException e) {
                        Log.e(TAG, "CameraAcccess exception: ", e);
                      }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {}
                  },
                  null);
            } catch (CameraAccessException e) {
              Log.e(TAG, "Failed to open camera: ", e);
            }
          }

          @Override
          public void onDisconnected(@NonNull CameraDevice camera) {

          }

          @Override
          public void onError(@NonNull CameraDevice camera, int error) {

          }
        }, mBackgroundHandler);
      } catch (CameraAccessException e) {
        Log.e(TAG, "Error getting camera ID list: ", e);
      }

      InputStream is = new ParcelFileDescriptor.AutoCloseInputStream(mParcelRead);

      // This will skip the MPEG4 header if this step fails we can't stream anything :(
      try {
        byte buffer[] = new byte[4];
        // Skip all atoms preceding mdat atom
        while (!Thread.interrupted()) {
          while (is.read() != 'm');
          is.read(buffer,0,3);
          if (buffer[0] == 'd' && buffer[1] == 'a' && buffer[2] == 't') break;
        }
      } catch (IOException e) {
        Log.e(TAG,"Couldn't skip mp4 header :/");
        stop();
        throw e;
      }

      // The packetizer encapsulates the bit stream in an RTP stream and send it over the network
      mPacketizer.setInputStream(is);
      mPacketizer.start();
      mStreaming = true;
    } catch (Exception e) {
      Log.e(TAG, "Exception: ", e);
      throw new ConfNotSupportedException(e.getMessage());
    }
  }

  private ImageReader mPreviewImageReader;

  @SuppressLint("MissingPermission")
  @Override
  protected void encodeWithMediaCodec() throws IOException {
    Log.d(TAG, "Video encoded using the MediaRecorderAPI");
    Codec[] encoders = CodecManager.findEncodersForMimeType(MIME_TYPE);

    createSockets();

    try {
      String mime = MediaFormat.MIMETYPE_VIDEO_AVC;
      mMediaCodec = MediaCodec.createEncoderByType(mime);
      MediaFormat mediaFormat = MediaFormat.createVideoFormat(mime, 1920, 1080);
      mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE,10000000);
      mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
      mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatSurface);
      mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);
      mMediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      Surface surface = mMediaCodec.createInputSurface();
//      mMediaCodec.setCallback(new Callback() {
//        FileOutputStream fout;
//        @Override
//        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
//
//        }
//
//        @Override
//        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index,
//            @NonNull BufferInfo info) {
//          if (fout == null) {
//            Log.e(TAG, "Creating codec input...");
//            try {
//              fout = new FileOutputStream("/sdcard/out.mp4");
//            } catch (FileNotFoundException e) {
//              e.printStackTrace();
//            }
//          }
//
//          ByteBuffer outputBuffer = codec.getOutputBuffer(index);
//          byte[] bytes = new byte[info.size];
//
//          outputBuffer.get(bytes);
//          try {
//            fout.write(bytes);
//            codec.releaseOutputBuffer(index, false);
//          } catch (IOException e) {
//            e.printStackTrace();
//          }
//
//
//        }
//
//        @Override
//        public void onError(@NonNull MediaCodec codec, @NonNull CodecException e) {
//
//        }
//
//        @Override
//        public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
//
//        }
//      });
      mMediaCodec.start();
      mPacketizer.setInputStream(new MediaCodecInputStream(mMediaCodec));
      mPacketizer.start();

      final CameraManager manager = (CameraManager) sContext.getSystemService(Context.CAMERA_SERVICE);
      try {
        String cameraId = manager.getCameraIdList()[0];
        manager.openCamera(cameraId, new StateCallback() {
          @SuppressLint("NewApi")
          @Override
          public void onOpened(@NonNull CameraDevice camera) {
            try {
              CaptureRequest.Builder previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
              previewRequestBuilder.addTarget(surface);
              camera.createCaptureSession(
                  Arrays.asList(surface),
                  new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                      previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                          CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                      previewRequestBuilder.set(
                          CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                          Range.create(30, 30));
                      CaptureRequest previewRequest = previewRequestBuilder.build();
                      try {
                        session.setRepeatingRequest(previewRequest, new CameraCaptureSession.CaptureCallback() {
                          @Override
                          public void onCaptureStarted(@NonNull CameraCaptureSession session,
                              @NonNull CaptureRequest request, long timestamp, long frameNumber) {
                            super.onCaptureStarted(session, request, timestamp, frameNumber);
                          }

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
                        }, mBackgroundHandler);
                      } catch (CameraAccessException e) {
                        Log.e(TAG, "CameraAcccess exception: ", e);
                      }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {}
                  },
                  null);
            } catch (CameraAccessException e) {
              Log.e(TAG, "Failed to open camera: ", e);
            }
          }

          @Override
          public void onDisconnected(@NonNull CameraDevice camera) {

          }

          @Override
          public void onError(@NonNull CameraDevice camera, int error) {

          }
        }, mBackgroundHandler);
      } catch (CameraAccessException e) {
        Log.e(TAG, "Error getting camera ID list: ", e);
      }
    } catch (Exception e) {
      Log.e(TAG, "Exception: ", e);
      throw new ConfNotSupportedException(e.getMessage());
    }
  }

  @Override
  public synchronized void start() throws IOException {
    if (!mStreaming) {
      ((H264Packetizer)mPacketizer).setStreamParameters(null, null);
      Log.e(TAG, "Ports: " + mRtpPort + " " + mRtcpPort);
      mPacketizer.setDestination(mDestination, mRtpPort, mRtcpPort);
      super.start();
    }
  }

  @Override
  public synchronized  String getSessionDescription() {
    return "m=video " + getDestinationPorts()[0] +" RTP/AVP 96\r\n" +
        "a=rtpmap:96 H264/90000\r\n" +
        "a=fmtp:96 packetization-mode=1;\r\n";
  }

}
