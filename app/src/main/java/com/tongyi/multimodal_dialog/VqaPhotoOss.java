package com.tongyi.multimodal_dialog;

import android.util.Log;

public class VqaPhotoOss {
    public enum VqaPhotoOssState {
        WAIT,
        EXECUTE_TAKE_PHOTO,
        EXECUTE_TAKE_PHOTO_SUCCESS,
        EXECUTE_TAKE_PHOTO_FAIL,
        EXECUTE_OSS_PHOTO,
        EXECUTE_OSS_PHOTO_SUCCESS,
        EXECUTE_OSS_PHOTO_FAIL
    }
    public VqaPhotoOssState state;
    public String photoOssUrl;

    public VqaPhotoOss(VqaPhotoOssState state, String photoOssUrl) {
        this.state = state;
        this.photoOssUrl = photoOssUrl;
    }

    public void setState(VqaPhotoOssState state) {
        Log.d("VqaPhotoOss", "setState: " + state);
        this.state = state;
    }

    public void setPhotoOssUrl(String photoOssUrl) {
        this.photoOssUrl = photoOssUrl;
    }

    public VqaPhotoOssState getState() {
        return state;
    }

    public String getPhotoOssUrl() {
        return photoOssUrl;
    }
}

