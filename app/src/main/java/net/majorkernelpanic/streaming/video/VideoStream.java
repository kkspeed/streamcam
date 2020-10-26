/*
 * Copyright (C) 2011-2015 GUIGUI Simon, fyhertz@gmail.com
 *
 * This file is part of libstreaming (https://github.com/fyhertz/libstreaming)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.majorkernelpanic.streaming.video;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCaptureSession.CaptureCallback;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraDevice.StateCallback;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import androidx.annotation.NonNull;
import java.io.IOException;
import java.util.Arrays;
import java.util.function.Consumer;
import net.majorkernelpanic.streaming.MediaStream;
import net.majorkernelpanic.streaming.Stream;
import net.majorkernelpanic.streaming.exceptions.CameraInUseException;
import net.majorkernelpanic.streaming.exceptions.InvalidSurfaceException;
import net.majorkernelpanic.streaming.gl.SurfaceView;
import net.majorkernelpanic.streaming.hw.EncoderDebugger;
import net.majorkernelpanic.streaming.rtp.MediaCodecInputStream;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.hardware.Camera.CameraInfo;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback;

/** 
 * Don't use this class directly.
 */
public abstract class VideoStream extends MediaStream {

	protected final static String TAG = "VideoStream";

	protected VideoQuality mRequestedQuality = VideoQuality.DEFAULT_VIDEO_QUALITY.clone();
	protected VideoQuality mQuality = mRequestedQuality.clone(); 
	protected SurfaceHolder.Callback mSurfaceHolderCallback = null;
	protected SurfaceView mSurfaceView = null;
	protected SharedPreferences mSettings = null;
	protected int mVideoEncoder, mCameraId = 0;
	protected int mRequestedOrientation = 0, mOrientation = 0;

	protected boolean mCameraOpenedManually = true;
	protected boolean mFlashEnabled = false;
	protected boolean mSurfaceReady = false;
	protected boolean mPreviewStarted = false;
	protected boolean mUpdated = false;
	
	protected String mMimeType;

	@NonNull private Context mContext;

	/** 
	 * Don't use this class directly.
	 * Uses CAMERA_FACING_BACK by default.
	 */
	public VideoStream(Context context) {
		this(CameraCharacteristics.LENS_FACING_BACK, context);
	}	

	/** 
	 * Don't use this class directly
	 * @param camera Can be either CameraInfo.CAMERA_FACING_BACK or CameraInfo.CAMERA_FACING_FRONT
	 */
	@SuppressLint("InlinedApi")
	public VideoStream(int camera, @NonNull Context context) {
		super();
		mContext = context;
		setCamera(camera);
	}

	/**
	 * Sets the camera that will be used to capture video.
	 * You can call this method at any time and changes will take effect next time you start the stream.
	 * @param camera Can be either CameraInfo.CAMERA_FACING_BACK or CameraInfo.CAMERA_FACING_FRONT
	 */
	public void setCamera(int camera) {
	  mCameraId = camera;
	}

	/**	Switch between the front facing and the back facing camera of the phone. 
	 * If {@link #startPreview()} has been called, the preview will be  briefly interrupted. 
	 * If {@link #start()} has been called, the stream will be  briefly interrupted.
	 * You should not call this method from the main thread if you are already streaming. 
	 * @throws IOException 
	 * @throws RuntimeException 
	 **/
	public void switchCamera() throws RuntimeException, IOException {}

	/**
	 * Returns the id of the camera currently selected. 
	 * Can be either {@link CameraInfo#CAMERA_FACING_BACK} or 
	 * {@link CameraInfo#CAMERA_FACING_FRONT}.
	 */
	public int getCamera() {
		return mCameraId;
	}

	/**
	 * Sets a Surface to show a preview of recorded media (video). 
	 * You can call this method at any time and changes will take effect next time you call {@link #start()}.
	 */
	public synchronized void setSurfaceView(SurfaceView view) {
		mSurfaceView = view;
		if (mSurfaceHolderCallback != null && mSurfaceView != null && mSurfaceView.getHolder() != null) {
			mSurfaceView.getHolder().removeCallback(mSurfaceHolderCallback);
		}
		if (mSurfaceView != null && mSurfaceView.getHolder() != null) {
			mSurfaceHolderCallback = new Callback() {
				@Override
				public void surfaceDestroyed(SurfaceHolder holder) {
					mSurfaceReady = false;
					stopPreview();
					Log.d(TAG,"Surface destroyed !");
				}
				@Override
				public void surfaceCreated(SurfaceHolder holder) {
					mSurfaceReady = true;
				}
				@Override
				public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
					Log.d(TAG,"Surface Changed !");
				}
			};
			mSurfaceView.getHolder().addCallback(mSurfaceHolderCallback);
			mSurfaceReady = true;
		}
	}

	/** Turns the LED on or off if phone has one. */
	public synchronized void setFlashState(boolean state) {}

	/** 
	 * Toggles the LED of the phone if it has one.
	 * You can get the current state of the flash with {@link VideoStream#getFlashState()}.
	 */
	public synchronized void toggleFlash() {
		setFlashState(!mFlashEnabled);
	}

	/** Indicates whether or not the flash of the phone is on. */
	public boolean getFlashState() {
		return mFlashEnabled;
	}

	/** 
	 * Sets the orientation of the preview.
	 * @param orientation The orientation of the preview
	 */
	public void setPreviewOrientation(int orientation) {
		mRequestedOrientation = orientation;
		mUpdated = false;
	}
	
	/** 
	 * Sets the configuration of the stream. You can call this method at any time 
	 * and changes will take effect next time you call {@link #configure()}.
	 * @param videoQuality Quality of the stream
	 */
	public void setVideoQuality(VideoQuality videoQuality) {
		if (!mRequestedQuality.equals(videoQuality)) {
			mRequestedQuality = videoQuality.clone();
			mUpdated = false;
		}
	}

	/** 
	 * Returns the quality of the stream.  
	 */
	public VideoQuality getVideoQuality() {
		return mRequestedQuality;
	}

	/**
	 * Some data (SPS and PPS params) needs to be stored when {@link #getSessionDescription()} is called 
	 * @param prefs The SharedPreferences that will be used to save SPS and PPS parameters
	 */
	public void setPreferences(SharedPreferences prefs) {
		mSettings = prefs;
	}

	/**
	 * Configures the stream. You need to call this before calling {@link #getSessionDescription()} 
	 * to apply your configuration of the stream.
	 */
	public synchronized void configure() throws IllegalStateException, IOException {
		super.configure();
		mOrientation = mRequestedOrientation;
	}	
	
	/**
	 * Starts the stream.
	 * This will also open the camera and display the preview 
	 * if {@link #startPreview()} has not already been called.
	 */
	public synchronized void start() throws IllegalStateException, IOException {
		if (!mPreviewStarted) mCameraOpenedManually = false;
		super.start();
		Log.d(TAG,"Stream configuration: FPS: "+mQuality.framerate+" Width: "+mQuality.resX+" Height: "+mQuality.resY);
	}

	/** Stops the stream. */
	public synchronized void stop() {
		super.stop();
		destroyCamera();
	}

	public synchronized void startPreview() 
			throws CameraInUseException, 
			InvalidSurfaceException, 
			RuntimeException {
		mCameraOpenedManually = true;
	}

	/**
	 * Stops the preview.
	 */
	public synchronized void stopPreview() {
		mCameraOpenedManually = false;
	}

	/**
	 * Video encoding is done by a MediaRecorder.
	 */
	protected void encodeWithMediaRecorder() {
		throw new IllegalStateException("Media recorder not supported.");
	}


	/**
	 * Video encoding is done by a MediaCodec.
	 */
	protected void encodeWithMediaCodec() throws RuntimeException, IOException {
		Log.d(TAG,"Video encoded using the MediaCodec API with a surface");

		Log.e(TAG, "quality: " + mQuality.bitrate + " " + mQuality.framerate + " " + mQuality.resX + " " + mQuality.resY);
		mMediaCodec = MediaCodec.createEncoderByType(mMimeType);
		MediaFormat mediaFormat = MediaFormat.createVideoFormat(mMimeType, mQuality.resX, mQuality.resY);
		mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, mQuality.bitrate);
		mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, mQuality.framerate);
		mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
		mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
		mMediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

		final Surface surface = mMediaCodec.createInputSurface();

		mMediaCodec.start();

		// Updates the parameters of the camera if needed
		try {
			openCamera(cameraDevice -> {
				try {
					CaptureRequest.Builder captureRequestBuilder = cameraDevice.createCaptureRequest(
							CameraDevice.TEMPLATE_PREVIEW);
					captureRequestBuilder.addTarget(surface);

					Surface previewSurface = mSurfaceView.getHolder().getSurface();
					captureRequestBuilder.addTarget(previewSurface);

					cameraDevice.createCaptureSession(
							Arrays.asList(surface, previewSurface),
							new CameraCaptureSession.StateCallback() {
								@Override
								public void onConfigured(@NonNull CameraCaptureSession session) {
									try {
										captureRequestBuilder.set(
												CaptureRequest.CONTROL_AF_MODE,
												CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
										captureRequestBuilder.set(
												CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
												Range.create(Math.max(mQuality.framerate, 15), Math.min(mQuality.framerate, 60)));

										final CaptureRequest request = captureRequestBuilder.build();
										session.setRepeatingRequest(request, new CaptureCallback() {
											int time = 0;
											int frameCount = 0;
											long now, oldNow;
											int trials = 0;

											@Override
											public void onCaptureCompleted(@NonNull CameraCaptureSession session,
													@NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
												super.onCaptureCompleted(session, request, result);
												//trials += 1;
												//now = System.nanoTime() / 1000;
												//if (trials > 3) {
												//	time += now - oldNow;
												//	frameCount++;
												//}
												//if (trials > 20) {
												//	mQuality.framerate = 1000000/(time/frameCount) + 1;
												//}
												//now = oldNow;
											}

											@Override
											public void onCaptureProgressed(@NonNull CameraCaptureSession session,
													@NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
												super.onCaptureProgressed(session, request, partialResult);
											}
										}, mBackgroundHandler);
									} catch (CameraAccessException e) {
									  Log.e(TAG, "Failed to set repeating request ", e);
									}
								}

								@Override
								public void onConfigureFailed(@NonNull CameraCaptureSession session) {

								}
							},
							null);
				} catch (CameraAccessException e) {
				  Log.e(TAG, "Unable to create capture request: ", e);
				}
			});
		} catch (CameraAccessException e) {
		  throw new RuntimeException("Unable to open camera: " + e);
		}

		// The packetizer encapsulates the bit stream in an RTP stream and send it over the network
		mPacketizer.setInputStream(new MediaCodecInputStream(mMediaCodec));
		mPacketizer.start();

		mStreaming = true;

	}

	/**
	 * Returns a description of the stream using SDP. 
	 * This method can only be called after {@link Stream#configure()}.
	 * @throws IllegalStateException Thrown when {@link Stream#configure()} wa not called.
	 */	
	public abstract String getSessionDescription() throws IllegalStateException;

	protected synchronized void destroyCamera() {
		if (mStreaming) super.stop();
		if (mCameraDevice != null) {
			mCameraDevice.close();
			mCameraDevice = null;
		}
		mStreaming = false;
	}

	@SuppressLint("MissingPermission")
	private void openCamera(final Consumer<CameraDevice> onOpen) throws CameraAccessException {
	  HandlerThread thread = new HandlerThread("CameraHandler");
	  thread.start();

	  mBackgroundHandler = new Handler(thread.getLooper());

		final CameraManager manager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
		for (String cameraId : manager.getCameraIdList()) {
		  CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
		  if (characteristics.get(CameraCharacteristics.LENS_FACING) == mCameraId) {
		    Log.e(TAG, "opening camera: " + cameraId);
		    manager.openCamera(cameraId, new StateCallback() {
					@Override
					public void onOpened(@NonNull CameraDevice camera) {
						mCameraDevice = camera;
						onOpen.accept(camera);
					}

					@Override
					public void onDisconnected(@NonNull CameraDevice camera) {
						camera.close();
					}

					@Override
					public void onError(@NonNull CameraDevice camera, int error) {
						camera.close();
					}
				}, mBackgroundHandler);
		  	break;
			}
		}
	}

	private CameraDevice mCameraDevice;
	private Handler mBackgroundHandler;
}
