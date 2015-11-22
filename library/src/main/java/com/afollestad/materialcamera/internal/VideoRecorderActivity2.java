package com.afollestad.materialcamera.internal;

import android.app.Fragment;
import android.support.annotation.NonNull;

public class VideoRecorderActivity2 extends BaseVideoRecorderActivity {

    @Override
    @NonNull
    public Fragment getFragment() {
        return Camera2VideoFragment.newInstance();
    }
}