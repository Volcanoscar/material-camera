package com.afollestad.materialcamera.internal;

import android.app.Fragment;
import android.support.annotation.NonNull;

public class VideoRecorderActivity extends BaseVideoRecorderActivity {

    @Override
    @NonNull
    public Fragment getFragment() {
        return CameraVideoFragment.newInstance();
    }
}