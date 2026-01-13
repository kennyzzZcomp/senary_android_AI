package com.tongyi.multimodal_dialog.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;
import androidx.core.content.ContextCompat;

public class AlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "AlarmReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        int alarmId = intent.getIntExtra("alarm_id", -1);
        String content = intent.getStringExtra("content");
        Log.d(TAG, "闹钟触发: id=" + alarmId + ", 内容=" + content);
        Toast.makeText(context, "闹钟到点: " + content, Toast.LENGTH_LONG).show();
        // TODO: 可在此处发通知或启动Activity等

        // 功能1: 使用预设音频进行播放
        // 构造AlarmPlaybackService 的 Intent
        Intent svcIntent = new Intent(context, AlarmPlaybackService.class);
        svcIntent.putExtra("alarm_id", alarmId);
        svcIntent.putExtra("content", content);
        // 启动前台服务
        ContextCompat.startForegroundService(context, svcIntent);


    }
}

