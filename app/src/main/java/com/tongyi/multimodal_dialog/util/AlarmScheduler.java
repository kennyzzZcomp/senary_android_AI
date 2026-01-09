package com.tongyi.multimodal_dialog.util;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.Calendar;
import java.util.Date;

/**
 * 封装闹钟解析与调度
 */
public class AlarmScheduler {
    private static final String TAG = "AlarmScheduler";

    /**
     * 根据 date(yyyy-MM-dd)、time(HH:mm) 解析为触发时间，若已过去则推迟到明天同一时刻。
     */
    public static long parseTriggerTimeMillis(String date, String time) {
        Calendar cal = Calendar.getInstance();
        if (date != null && !date.isEmpty()) {
            String[] parts = date.split("-");
            if (parts.length == 3) {
                int year = safeParse(parts[0], cal.get(Calendar.YEAR));
                int month = safeParse(parts[1], cal.get(Calendar.MONTH) + 1) - 1; // Calendar month 0-based
                int day = safeParse(parts[2], cal.get(Calendar.DAY_OF_MONTH));
                cal.set(Calendar.YEAR, year);
                cal.set(Calendar.MONTH, month);
                cal.set(Calendar.DAY_OF_MONTH, day);
            }
        }
        if (time != null && !time.isEmpty()) {
            String[] parts = time.split(":");
            if (parts.length >= 2) {
                int hour = safeParse(parts[0], cal.get(Calendar.HOUR_OF_DAY));
                int minute = safeParse(parts[1], cal.get(Calendar.MINUTE));
                cal.set(Calendar.HOUR_OF_DAY, hour);
                cal.set(Calendar.MINUTE, minute);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
            }
        }
        long triggerAtMillis = cal.getTimeInMillis();
        if (triggerAtMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_MONTH, 1);
            triggerAtMillis = cal.getTimeInMillis();
        }
        return triggerAtMillis;
    }

    /**
     * 安排一次性闹钟
     */
    public static boolean scheduleOneShot(Context context, int alarmId, String content, long triggerAtMillis) {
        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(context, AlarmReceiver.class);
            intent.putExtra("alarm_id", alarmId);
            intent.putExtra("content", content);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(context, alarmId, intent, flags);
            if (alarmManager != null) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
                Log.d(TAG, "闹钟已设置: id=" + alarmId + ", trigger=" + new Date(triggerAtMillis));
                return true;
            } else {
                Log.e(TAG, "AlarmManager 获取失败");
                return false;
            }
        } catch (Exception ex) {
            Log.e(TAG, "设置闹钟失败", ex);
            return false;
        }
    }

    /**
     * 取消闹钟
     */
    public static void cancel(Context context, int alarmId) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, AlarmReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(context, alarmId, intent, flags);
        if (alarmManager != null) {
            alarmManager.cancel(pi);
        }
    }

    private static int safeParse(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception ignored) { return def; }
    }
}

