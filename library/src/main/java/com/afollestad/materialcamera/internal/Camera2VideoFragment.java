package com.afollestad.materialcamera.internal;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Toast;

import com.afollestad.materialcamera.R;
import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static com.afollestad.materialcamera.internal.BaseVideoRecorderActivity.CAMERA_POSITION_BACK;
import static com.afollestad.materialcamera.internal.BaseVideoRecorderActivity.CAMERA_POSITION_FRONT;
import static com.afollestad.materialcamera.internal.BaseVideoRecorderActivity.CAMERA_POSITION_UNKNOWN;

/**
 * @author Aidan Follestad (afollestad)
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class Camera2VideoFragment extends BaseCameraVideoFragment implements View.OnClickListener {

    private CameraDevice mCameraDevice;
    private CameraCaptureSession mPreviewSession;
    private AutoFitTextureView mTextureView;

    private Size mPreviewSize;
    private Size mVideoSize;
    @Degrees.DegreeUnits
    private int mSensorOrientation;
    @Degrees.DegreeUnits
    private int mDisplayOrientation;
    private CaptureRequest.Builder mPreviewBuilder;
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;
    private final Semaphore mCameraOpenCloseLock = new Semaphore(1);

    private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        }
    };

    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            mCameraDevice = cameraDevice;
            startPreview();
            mCameraOpenCloseLock.release();
            if (null != mTextureView) {
                configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
            Activity activity = getActivity();
            if (null != activity) {
                activity.finish();
            }
        }
    };

    public static Camera2VideoFragment newInstance() {
        Camera2VideoFragment fragment = new Camera2VideoFragment();
        fragment.setRetainInstance(true);
        return fragment;
    }

    /**
     * In this sample, we choose a video size with 3x4 aspect ratio. Also, we don't use sizes larger
     * than 1080p, since MediaRecorder cannot handle such a high-resolution video.
     *
     * @param choices The list of available sizes
     * @return The video size
     */
    private static Size chooseVideoSize(Size[] choices) {
        Size backupSize = null;
        for (Size size : choices) {
            if (size.getHeight() <= PREFERRED_PIXEL_HEIGHT) {
                if (size.getWidth() == size.getHeight() * PREFERRED_ASPECT_RATIO)
                    return size;
                backupSize = size;
            }
        }
        if (backupSize != null) return backupSize;
        LOG(Camera2VideoFragment.class, "Couldn't find any suitable video size");
        return choices[choices.length - 1];
    }

    private static Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * h / w &&
                    option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            LOG(Camera2VideoFragment.class, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mTextureView = (AutoFitTextureView) view.findViewById(R.id.texture);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mTextureView = null;
    }

    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();
        if (mTextureView.isAvailable()) {
            openCamera();
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    public void onPause() {
        stopBackgroundThread();
        super.onPause();
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        stopCounter();
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void openCamera() {
        final int width = mTextureView.getWidth();
        final int height = mTextureView.getHeight();
        final Activity activity = getActivity();
        if (null == activity || activity.isFinishing()) return;
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS))
                throwError(new Exception("Time out waiting to lock camera opening."));
            if (mInterface.getFrontCamera() == null || mInterface.getBackCamera() == null) {
                for (String cameraId : manager.getCameraIdList()) {
                    if (cameraId == null) continue;
                    if (mInterface.getFrontCamera() != null && mInterface.getBackCamera() != null)
                        break;
                    CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                    //noinspection ConstantConditions
                    int facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    if (facing == CameraCharacteristics.LENS_FACING_FRONT)
                        mInterface.setFrontCamera(cameraId);
                    else if (facing == CameraCharacteristics.LENS_FACING_BACK)
                        mInterface.setBackCamera(cameraId);
                }
            }
            if (mInterface.getCurrentCameraPosition() == CAMERA_POSITION_UNKNOWN) {
                if (getArguments().getBoolean("default_to_front_facing", false)) {
                    // Check front facing first
                    if (mInterface.getFrontCamera() != null) {
                        mButtonFacing.setImageResource(R.drawable.ic_camera_rear);
                        mInterface.setCameraPosition(CAMERA_POSITION_FRONT);
                    } else {
                        mButtonFacing.setImageResource(R.drawable.ic_camera_front);
                        if (mInterface.getBackCamera() != null)
                            mInterface.setCameraPosition(CAMERA_POSITION_BACK);
                        else mInterface.setCameraPosition(CAMERA_POSITION_UNKNOWN);
                    }
                } else {
                    // Check back facing first
                    if (mInterface.getBackCamera() != null) {
                        mButtonFacing.setImageResource(R.drawable.ic_camera_front);
                        mInterface.setCameraPosition(CAMERA_POSITION_BACK);
                    } else {
                        mButtonFacing.setImageResource(R.drawable.ic_camera_rear);
                        if (mInterface.getFrontCamera() != null)
                            mInterface.setCameraPosition(CAMERA_POSITION_FRONT);
                        else mInterface.setCameraPosition(CAMERA_POSITION_UNKNOWN);
                    }
                }
            }

            // Choose the sizes for camera preview and video recording
            CameraCharacteristics characteristics = manager.getCameraCharacteristics((String) mInterface.getCurrentCameraId());
            StreamConfigurationMap map = characteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;
            mVideoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));
            mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                    width, height, mVideoSize);

            //noinspection ConstantConditions,ResourceType
            mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

            @Degrees.DegreeUnits
            int deviceRotation = Degrees.getDisplayRotation(getActivity());
            mDisplayOrientation = Degrees.getDisplayOrientation(
                    mSensorOrientation, deviceRotation, getCurrentCameraPosition() == CAMERA_POSITION_FRONT);
            Log.d("Camera2VideoFragment", String.format("Orientations: Sensor = %d˚, Device = %d˚, Display = %d˚",
                    mSensorOrientation, deviceRotation, mDisplayOrientation));

            int orientation = VideoStreamView.getScreenOrientation(activity);
            if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ||
                    orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) {
                mTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            } else {
                mTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
            }
            configureTransform(width, height);
            mMediaRecorder = new MediaRecorder();
            // noinspection ResourceType
            manager.openCamera((String) mInterface.getCurrentCameraId(), mStateCallback, null);
        } catch (CameraAccessException e) {
            throwError(new Exception("Cannot access the camera.", e));
        } catch (NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            new ErrorDialog().show(getFragmentManager(), "dialog");
        } catch (InterruptedException e) {
            throwError(new Exception("Interrupted while trying to lock camera opening.", e));
        }
    }

    @Override
    public void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mMediaRecorder) {
                mMediaRecorder.release();
                mMediaRecorder = null;
            }
        } catch (InterruptedException e) {
            throwError(new Exception("Interrupted while trying to lock camera opening.", e));
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    private void startPreview() {
        if (null == mCameraDevice || !mTextureView.isAvailable() || null == mPreviewSize)
            return;
        try {
            if (!setUpMediaRecorder()) return;
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            List<Surface> surfaces = new ArrayList<>();

            Surface previewSurface = new Surface(texture);
            surfaces.add(previewSurface);
            mPreviewBuilder.addTarget(previewSurface);

            Surface recorderSurface = mMediaRecorder.getSurface();
            surfaces.add(recorderSurface);
            mPreviewBuilder.addTarget(recorderSurface);

            mCameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    mPreviewSession = cameraCaptureSession;
                    updatePreview();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    throwError(new Exception("Camera configuration failed"));
                }
            }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void updatePreview() {
        if (null == mCameraDevice) {
            return;
        }
        try {
            setUpCaptureRequestBuilder(mPreviewBuilder);
            HandlerThread thread = new HandlerThread("CameraPreview");
            thread.start();
            mPreviewSession.setRepeatingRequest(mPreviewBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void setUpCaptureRequestBuilder(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        Activity activity = getActivity();
        if (null == mTextureView || null == mPreviewSize || null == activity) {
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        mTextureView.setTransform(matrix);
    }

    private boolean setUpMediaRecorder() {
        final Activity activity = getActivity();
        if (null == activity) return false;
        if (mMediaRecorder == null)
            mMediaRecorder = new MediaRecorder();
        boolean canUseAudio = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            canUseAudio = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (canUseAudio) {
            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        } else {
            Toast.makeText(getActivity(), R.string.mcam_no_audio_access, Toast.LENGTH_LONG).show();
        }
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mMediaRecorder.setVideoEncodingBitRate(10000000);
        mMediaRecorder.setVideoFrameRate(15);
        mMediaRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        if (canUseAudio)
            mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        Uri uri = Uri.fromFile(getOutputMediaFile());
        mOutputUri = uri.toString();
        mMediaRecorder.setOutputFile(uri.getPath());
        mMediaRecorder.setOrientationHint(mDisplayOrientation);

        try {
            mMediaRecorder.prepare();
            return true;
        } catch (Throwable e) {
            throwError(new Exception("Failed to prepare the media recorder: " + e.getMessage(), e));
            return false;
        }
    }

    @Override
    public void startRecordingVideo() {
        super.startRecordingVideo();
        try {
            // UI
            mButtonVideo.setImageResource(R.drawable.ic_action_stop);
            mButtonFacing.setVisibility(View.GONE);

            // Only start counter if count down wasn't already started
            if (!mInterface.hasLengthLimit()) {
                mInterface.setRecordingStart(System.currentTimeMillis());
                startCounter();
            }

            // Start recording
            mMediaRecorder.start();
            mIsRecording = true;

            mButtonVideo.setEnabled(false);
            mButtonVideo.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mButtonVideo.setEnabled(true);
                }
            }, 1000);
        } catch (Throwable t) {
            t.printStackTrace();
            mInterface.setRecordingStart(-1);
            stopRecordingVideo(false);
            throwError(new Exception("Failed to start recording: " + t.getMessage(), t));
        }
    }

    @Override
    public void stopRecordingVideo(boolean reachedZero) {
        super.stopRecordingVideo(reachedZero);
        mIsRecording = false;

        if (mInterface.hasLengthLimit() && mInterface.shouldAutoSubmit() &&
                (mInterface.getRecordingStart() < 0 || mMediaRecorder == null)) {
            stopCounter();
            releaseRecorder();
            mInterface.onShowPreview(mOutputUri, reachedZero);
            return;
        }

        if (!mInterface.didRecord())
            mOutputUri = null;

        releaseRecorder();
        mButtonVideo.setImageResource(R.drawable.ic_action_record);
        mButtonFacing.setVisibility(View.VISIBLE);
        if (mInterface.getRecordingStart() > -1 && getActivity() != null)
            mInterface.onShowPreview(mOutputUri, reachedZero);

        stopCounter();
    }

    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    public static class ErrorDialog extends DialogFragment {
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Activity activity = getActivity();
            return new MaterialDialog.Builder(activity)
                    .content("This device doesn't support the Camera2 API.")
                    .positiveText(android.R.string.ok)
                    .onAny(new MaterialDialog.SingleButtonCallback() {
                        @Override
                        public void onClick(@NonNull MaterialDialog materialDialog, @NonNull DialogAction dialogAction) {
                            activity.finish();
                        }
                    }).build();
        }
    }
}