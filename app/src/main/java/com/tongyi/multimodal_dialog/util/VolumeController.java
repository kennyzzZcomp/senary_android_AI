package com.tongyi.multimodal_dialog.util;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

/**
 * 控制“硬件平台音量”的最小封装：本 Demo 的 TTS(AudioTrack/USAGE_MEDIA) 和音乐(MediaPlayer) 都走媒体流，
 * 所以这里统一控制 {@link AudioManager#STREAM_MUSIC}。
 */
public final class VolumeController {
    private static final String TAG = "VolumeController";

    /**
     * Demo 里最符合预期的输出音量流：媒体音量。
     * 如果你们的 RTC 硬件链路使用通话流（STREAM_VOICE_CALL），可以在这里按模式切换。
     */
    private static final int STREAM_TYPE = AudioManager.STREAM_MUSIC;

    /** 默认音量档位：最大音量的 50%（至少为 1） */
    private static final float DEFAULT_RATIO = 0.5f;

    private VolumeController() {
    }

    public static void increase(Context context) {
        adjust(context, AudioManager.ADJUST_RAISE);
    }

    public static void decrease(Context context) {
        adjust(context, AudioManager.ADJUST_LOWER);
    }

    /**
     * 按固定步进调整音量（例如 +10 / -10）。会自动裁剪到 [0, max]。
     */
    public static void adjustByStep(Context context, int step) {
        AudioManager am = getAudioManager(context);
        if (am == null) return;

        int max = am.getStreamMaxVolume(STREAM_TYPE);
        int cur = am.getStreamVolume(STREAM_TYPE);
        int target = clamp(cur + step, 0, max);

        am.setStreamVolume(STREAM_TYPE, target, AudioManager.FLAG_SHOW_UI);
        Log.d(TAG, "adjustByStep stream=" + STREAM_TYPE + ", step=" + step + ", " + cur + "->" + target + "/" + max);
    }

    /**
     * 设置到指定音量（会自动裁剪到 [0, max]）。
     */
    public static void set(Context context, int volume) {
        AudioManager am = getAudioManager(context);
        if (am == null) return;

        int max = am.getStreamMaxVolume(STREAM_TYPE);
        int target = clamp(volume, 0, max);
        am.setStreamVolume(STREAM_TYPE, target, AudioManager.FLAG_SHOW_UI);
        Log.d(TAG, "set stream=" + STREAM_TYPE + ", target=" + target + "/" + max);
    }

    /**
     * 设置到“默认音量”。
     * 你也可以把 DEFAULT_RATIO 替换成固定档位，或者从配置下发。
     */
    public static void setToDefault(Context context) {
        AudioManager am = getAudioManager(context);
        if (am == null) return;

        int max = am.getStreamMaxVolume(STREAM_TYPE);
        if (max <= 0) return;

        int target = Math.max(1, Math.round(max * DEFAULT_RATIO));
        target = Math.min(target, max);
        am.setStreamVolume(STREAM_TYPE, target, AudioManager.FLAG_SHOW_UI);
        Log.d(TAG, "setToDefault stream=" + STREAM_TYPE + ", target=" + target + "/" + max);
    }

    public static int getCurrent(Context context) {
        AudioManager am = getAudioManager(context);
        if (am == null) return -1;
        return am.getStreamVolume(STREAM_TYPE);
    }

    public static int getMax(Context context) {
        AudioManager am = getAudioManager(context);
        if (am == null) return -1;
        return am.getStreamMaxVolume(STREAM_TYPE);
    }

    private static void adjust(Context context, int direction) {
        AudioManager am = getAudioManager(context);
        if (am == null) return;

        am.adjustStreamVolume(STREAM_TYPE, direction, AudioManager.FLAG_SHOW_UI);
        Log.d(TAG, "adjust stream=" + STREAM_TYPE + ", direction=" + direction + ", now=" + am.getStreamVolume(STREAM_TYPE));
    }

    private static AudioManager getAudioManager(Context context) {
        if (context == null) return null;
        try {
            return (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        } catch (Exception e) {
            Log.e(TAG, "getAudioManager failed", e);
            return null;
        }
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}
