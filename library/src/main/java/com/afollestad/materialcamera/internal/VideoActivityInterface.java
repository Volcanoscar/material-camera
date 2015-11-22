package com.afollestad.materialcamera.internal;

import android.support.annotation.Nullable;

/**
 * @author Aidan Follestad (afollestad)
 */
interface VideoActivityInterface {

    void onRetry(@Nullable String outputUri);

    void onShowPreview(@Nullable String outputUri, boolean countdownIsAtZero);

    void setRecordingStart(long start);

    void setRecordingEnd(long end);

    long getRecordingStart();

    long getRecordingEnd();

    boolean hasLengthLimit();

    long getLengthLimit();

    void setCameraPosition(int position);

    void toggleCameraPosition();

    Object getCurrentCameraId();

    @BaseVideoRecorderActivity.CameraPosition
    int getCurrentCameraPosition();

    void setFrontCamera(Object id);

    void setBackCamera(Object id);

    Object getFrontCamera();

    Object getBackCamera();

    void useVideo(String uri);

    boolean shouldAutoSubmit();

    boolean allowRetry();

    void setDidRecord(boolean didRecord);

    boolean didRecord();
}