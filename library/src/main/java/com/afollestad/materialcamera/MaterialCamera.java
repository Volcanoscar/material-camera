package com.afollestad.materialcamera;

import android.app.Activity;
import android.content.Intent;
import android.support.annotation.AttrRes;
import android.support.annotation.ColorInt;
import android.support.annotation.ColorRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;

import com.afollestad.materialcamera.internal.CameraUtil;
import com.afollestad.materialcamera.internal.VideoRecorderActivity;
import com.afollestad.materialcamera.internal.VideoRecorderActivity2;
import com.afollestad.materialdialogs.util.DialogUtils;

import java.io.File;

/**
 * @author Aidan Follestad (afollestad)
 */
public class MaterialCamera {

    public static final String ERROR_EXTRA = "mcam_error";

    private Activity mContext;
    private long mLengthLimit = -1;
    private boolean mAllowRetry = true;
    private boolean mAutoSubmit = false;
    private String mSaveDir;
    private int mPrimaryColor;
    private boolean mShowPortraitWarning = true;
    private boolean mDefaultToFrontFacing = false;

    public MaterialCamera(@NonNull Activity context) {
        mContext = context;
        mPrimaryColor = DialogUtils.resolveColor(context, R.attr.colorPrimary);
    }

    public MaterialCamera lengthLimitMillis(long lengthLimitMs) {
        mLengthLimit = lengthLimitMs;
        return this;
    }

    public MaterialCamera lengthLimitSeconds(float lengthLimitSec) {
        return lengthLimitMillis((int) (lengthLimitSec * 1000f));
    }

    public MaterialCamera lengthLimitMinutes(float lengthLimitMin) {
        return lengthLimitMillis((int) (lengthLimitMin * 1000f * 60f));
    }

    public MaterialCamera allowRetry(boolean allowRetry) {
        mAllowRetry = allowRetry;
        return this;
    }

    public MaterialCamera autoSubmit(boolean autoSubmit) {
        mAutoSubmit = autoSubmit;
        return this;
    }

    public MaterialCamera saveDir(@Nullable File dir) {
        if (dir == null) return saveDir((String) null);
        return saveDir(dir.getAbsolutePath());
    }

    public MaterialCamera saveDir(@Nullable String dir) {
        mSaveDir = dir;
        return this;
    }

    public MaterialCamera primaryColor(@ColorInt int color) {
        mPrimaryColor = color;
        return this;
    }

    public MaterialCamera primaryColorRes(@ColorRes int colorRes) {
        return primaryColor(ContextCompat.getColor(mContext, colorRes));
    }

    public MaterialCamera primaryColorAttr(@AttrRes int colorAttr) {
        return primaryColor(DialogUtils.resolveColor(mContext, colorAttr));
    }

    public MaterialCamera showPortraitWarning(boolean show) {
        mShowPortraitWarning = show;
        return this;
    }

    public MaterialCamera defaultToFrontFacing(boolean frontFacing) {
        mDefaultToFrontFacing = frontFacing;
        return this;
    }

    public Intent getIntent() {
        final Class<?> cls = CameraUtil.hasCamera2(mContext) ?
                VideoRecorderActivity2.class : VideoRecorderActivity.class;
        return new Intent(mContext, cls)
                .putExtra("length_limit", mLengthLimit)
                .putExtra("allow_retry", mAllowRetry)
                .putExtra("auto_submit", mAutoSubmit)
                .putExtra("save_dir", mSaveDir)
                .putExtra("primary_color", mPrimaryColor)
                .putExtra("show_portrait_warning", mShowPortraitWarning)
                .putExtra("default_to_front_facing", mDefaultToFrontFacing);
    }

    public void start(int requestCode) {
        mContext.startActivityForResult(getIntent(), requestCode);
    }
}