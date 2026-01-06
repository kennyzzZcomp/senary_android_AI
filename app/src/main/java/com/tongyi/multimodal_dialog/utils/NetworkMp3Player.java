package com.tongyi.multimodal_dialog.utils;

import android.media.MediaPlayer;
import android.util.Log;

import java.io.IOException;

/**
 * 网络MP3播放工具类
 * 支持通过URL播放MP3文件，提供播放、停止和播放完成回调功能
 */
public class NetworkMp3Player {
    private static final String TAG = "NetworkMp3Player";
    
    private MediaPlayer mediaPlayer;
    private OnPlayCallback playCallback;
    private boolean isPlaying = false;
    private String currentUrl = null;

    /**
     * 播放回调接口
     */
    public interface OnPlayCallback {
        /**
         * 播放开始回调
         */
        void onPlayStart();

        /**
         * 播放完成回调
         */
        void onPlayComplete();

        /**
         * 播放错误回调
         * @param error 错误信息
         */
        void onPlayError(String error);
    }

    public NetworkMp3Player() {
        initMediaPlayer();
    }

    /**
     * 初始化MediaPlayer
     */
    private void initMediaPlayer() {
        if (mediaPlayer == null) {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    isPlaying = false;
                    if (playCallback != null) {
                        playCallback.onPlayComplete();
                    }
                }
            });

            mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    isPlaying = false;
                    String errorMsg = "播放错误: what=" + what + ", extra=" + extra;
                    Log.e(TAG, errorMsg);
                    if (playCallback != null) {
                        playCallback.onPlayError(errorMsg);
                    }
                    return true;
                }
            });

            mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    if (playCallback != null) {
                        playCallback.onPlayStart();
                    }
                    mediaPlayer.start();
                    isPlaying = true;
                }
            });
        }
    }

    /**
     * 播放网络MP3
     * @param url MP3文件的URL地址
     * @param callback 播放回调
     */
    public void play(String url, OnPlayCallback callback) {
        if (url == null || url.isEmpty()) {
            if (callback != null) {
                callback.onPlayError("URL不能为空");
            }
            return;
        }

        this.playCallback = callback;
        this.currentUrl = url; // 保存当前播放的URL

        try {
            stop(); // 停止当前播放
            initMediaPlayer(); // 重新初始化

            mediaPlayer.setDataSource(url);
            mediaPlayer.prepareAsync(); // 异步准备播放
        } catch (IOException e) {
            Log.e(TAG, "播放失败: " + e.getMessage());
            if (playCallback != null) {
                playCallback.onPlayError("播放失败: " + e.getMessage());
            }
        } catch (Exception e) {
            Log.e(TAG, "播放异常: " + e.getMessage());
            if (playCallback != null) {
                playCallback.onPlayError("播放异常: " + e.getMessage());
            }
        }
    }

    /**
     * 停止播放
     */
    public void stop() {
        if (mediaPlayer != null && isPlaying) {
            try {
                mediaPlayer.stop();
                mediaPlayer.reset();
                isPlaying = false;
                currentUrl = null; // 清除当前播放的URL
            } catch (Exception e) {
                Log.e(TAG, "停止播放失败: " + e.getMessage());
            }
        }
    }

    /**
     * 暂停播放
     */
    public void pause() {
        if (mediaPlayer != null && isPlaying) {
            mediaPlayer.pause();
            isPlaying = false;
        }
    }

    /**
     * 恢复播放
     */
    public void resume() {
        if (mediaPlayer != null && !isPlaying) {
            mediaPlayer.start();
            isPlaying = true;
        }
    }

    /**
     * 是否正在播放
     * @return true表示正在播放，false表示未播放
     */
    public boolean isPlaying() {
        return isPlaying;
    }

    /**
     * 释放资源
     */
    public void release() {
        stop();
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        isPlaying = false;
    }

    /**
     * 获取当前播放的URL
     * @return 当前播放的URL，如果没有播放则返回null
     */
    public String getCurrentUrl() {
        return currentUrl;
    }
}