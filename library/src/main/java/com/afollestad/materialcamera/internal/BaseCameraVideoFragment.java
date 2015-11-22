package com.afollestad.materialcamera.internal;

import android.app.Activity;
import android.app.Fragment;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import com.afollestad.materialcamera.MaterialCamera;
import com.afollestad.materialcamera.R;
import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;
import com.telly.mrvector.MrVector;

import java.io.File;

import static android.app.Activity.RESULT_CANCELED;
import static com.afollestad.materialcamera.internal.BaseVideoRecorderActivity.CAMERA_POSITION_BACK;

/**
 * @author Aidan Follestad (afollestad)
 */
abstract class BaseCameraVideoFragment extends Fragment implements OutputUriInterface, View.OnClickListener {

    protected ImageButton mButtonVideo;
    protected ImageButton mButtonFacing;
    protected TextView mRecordDuration;

    protected static Drawable mRecordIcon;
    protected static Drawable mStopIcon;
    protected static Drawable mCameraFrontIcon;
    protected static Drawable mCameraBackIcon;

    protected static final float PREFERRED_ASPECT_RATIO = 4f / 3f;
    protected static final int PREFERRED_PIXEL_HEIGHT = 480;

    protected String mOutputUri;
    protected VideoActivityInterface mInterface;
    protected boolean mIsRecording;
    protected Handler mPositionHandler;
    protected MediaRecorder mMediaRecorder;

    protected static void LOG(Object context, String message) {
        Log.d(context instanceof Class<?> ? ((Class<?>) context).getSimpleName() :
                context.getClass().getSimpleName(), message);
    }

    private final Runnable mPositionUpdater = new Runnable() {
        @Override
        public void run() {
            if (mInterface == null || mRecordDuration == null) return;
            final long mRecordStart = mInterface.getRecordingStart();
            final long mRecordEnd = mInterface.getRecordingEnd();
            if (mRecordStart == -1 && mRecordEnd == -1) return;
            final long now = System.currentTimeMillis();
            if (mRecordEnd != -1) {
                if (now >= mRecordEnd) {
                    mInterface.setRecordingStart(-1);
                    stopRecordingVideo(true);
                } else {
                    long diff = mRecordEnd - now;
                    if (diff <= (1000 * 11))
                        mRecordDuration.setTextColor(ContextCompat.getColor(getActivity(), R.color.mcam_material_red_500));
                    mRecordDuration.setText(String.format("-%s", CameraUtil.getDurationString(diff)));
                }
            } else {
                mRecordDuration.setText(CameraUtil.getDurationString(now - mRecordStart));
            }
            if (mPositionHandler != null)
                mPositionHandler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Resources r = getResources();
        if (mRecordIcon == null)
            mRecordIcon = MrVector.inflate(r, R.drawable.ic_action_record);
        if (mStopIcon == null)
            mStopIcon = MrVector.inflate(r, R.drawable.ic_action_stop);
        if (mCameraFrontIcon == null)
            mCameraFrontIcon = MrVector.inflate(r, R.drawable.ic_camera_front);
        if (mCameraBackIcon == null)
            mCameraBackIcon = MrVector.inflate(r, R.drawable.ic_camera_rear);
    }

    @Override
    public final View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_videocapture, container, false);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mRecordIcon = null;
        mStopIcon = null;
        mCameraFrontIcon = null;
        mCameraBackIcon = null;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mButtonVideo = (ImageButton) view.findViewById(R.id.video);
        mButtonFacing = (ImageButton) view.findViewById(R.id.facing);
        mRecordDuration = (TextView) view.findViewById(R.id.recordDuration);
        mButtonFacing.setImageDrawable(mInterface.getCurrentCameraPosition() == CAMERA_POSITION_BACK ?
                mCameraFrontIcon : mCameraBackIcon);
        if (mMediaRecorder != null && mIsRecording)
            mButtonVideo.setImageDrawable(mStopIcon);
        else
            mButtonVideo.setImageDrawable(mRecordIcon);

        mButtonVideo.setOnClickListener(this);
        mButtonFacing.setOnClickListener(this);

        final int primaryColor = getArguments().getInt("primary_color");
        view.findViewById(R.id.controlsFrame).setBackgroundColor(CameraUtil.darkenColor(primaryColor));

        if (savedInstanceState != null)
            mOutputUri = savedInstanceState.getString("output_uri");
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mRecordIcon = null;
        mStopIcon = null;
        mCameraFrontIcon = null;
        mCameraBackIcon = null;
        mButtonVideo = null;
        mButtonFacing = null;
        mRecordDuration = null;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mInterface != null && mInterface.hasLengthLimit()) {
            if (mInterface.getRecordingStart() == -1)
                mInterface.setRecordingStart(System.currentTimeMillis());
            startCounter();
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public final void onAttach(Activity activity) {
        super.onAttach(activity);
        mInterface = (VideoActivityInterface) activity;
    }

    @NonNull
    protected final File getOutputMediaFile() {
        return CameraUtil.makeTempFile(getActivity(), getArguments().getString("save_dir"), ".mp4");
    }

    public abstract void openCamera();

    public abstract void closeCamera();

    @Override
    public void onPause() {
        super.onPause();
        closeCamera();
        releaseRecorder();
        stopCounter();
    }

    @Override
    public final void onDetach() {
        super.onDetach();
        mInterface = null;
    }

    public final void startCounter() {
        if (mPositionHandler == null)
            mPositionHandler = new Handler();
        else mPositionHandler.removeCallbacks(mPositionUpdater);
        mPositionHandler.post(mPositionUpdater);
    }

    @BaseVideoRecorderActivity.CameraPosition
    public final int getCurrentCameraPosition() {
        if (mInterface == null) return BaseVideoRecorderActivity.CAMERA_POSITION_UNKNOWN;
        return mInterface.getCurrentCameraPosition();
    }

    public final int getCurrentCameraId() {
        if (mInterface.getCurrentCameraPosition() == BaseVideoRecorderActivity.CAMERA_POSITION_BACK)
            return (Integer) mInterface.getBackCamera();
        else return (Integer) mInterface.getFrontCamera();
    }

    public final void stopCounter() {
        if (mPositionHandler != null) {
            mPositionHandler.removeCallbacks(mPositionUpdater);
            mPositionHandler = null;
        }
    }

    public final void releaseRecorder() {
        if (mMediaRecorder != null) {
            try {
                mMediaRecorder.stop();
            } catch (IllegalStateException ignored) {
            }
            mMediaRecorder.reset();
            mMediaRecorder.release();
            mMediaRecorder = null;
        }
    }

    public void startRecordingVideo() {
        final int orientation = Degrees.getActivityOrientation(getActivity());
        //noinspection ResourceType
        getActivity().setRequestedOrientation(orientation);
    }

    public void stopRecordingVideo(boolean reachedZero) {
        getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    @Override
    public final void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("output_uri", mOutputUri);
    }

    @Override
    public final String getOutputUri() {
        return mOutputUri;
    }

    protected final void throwError(Exception e) {
        Activity act = getActivity();
        if (act != null) {
            act.setResult(RESULT_CANCELED, new Intent().putExtra(MaterialCamera.ERROR_EXTRA, e));
            act.finish();
        }
    }

    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.facing) {
            mInterface.toggleCameraPosition();
            mButtonFacing.setImageResource(mInterface.getCurrentCameraPosition() == BaseVideoRecorderActivity.CAMERA_POSITION_BACK ?
                    R.drawable.ic_camera_front : R.drawable.ic_camera_rear);
            closeCamera();
            openCamera();
        } else if (view.getId() == R.id.video) {
            if (mIsRecording) {
                stopRecordingVideo(false);
            } else {
                if (getArguments().getBoolean("show_portrait_warning", true) &&
                        Degrees.isPortrait(getActivity())) {
                    new MaterialDialog.Builder(getActivity())
                            .title(R.string.mcam_portrait)
                            .content(R.string.mcam_portrait_warning)
                            .positiveText(R.string.mcam_yes)
                            .negativeText(android.R.string.cancel)
                            .onPositive(new MaterialDialog.SingleButtonCallback() {
                                @Override
                                public void onClick(@NonNull MaterialDialog materialDialog, @NonNull DialogAction dialogAction) {
                                    startRecordingVideo();
                                }
                            })
                            .show();
                } else {
                    startRecordingVideo();
                }
            }
        }
    }
}