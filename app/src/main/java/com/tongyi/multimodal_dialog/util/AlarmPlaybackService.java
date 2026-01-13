package com.tongyi.multimodal_dialog.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.app.PendingIntent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import android.media.RingtoneManager;

import java.io.File;

public class AlarmPlaybackService extends Service {
	private static final String TAG = "AlarmPlaybackService";
	private static final String CHANNEL_ID = "alarm_playback";
	public static final String ACTION_STOP = "com.tongyi.multimodal_dialog.util.AlarmPlaybackService.STOP";
	public static final String EXTRA_STOP_TIMEOUT_MS = "stop_timeout_ms";
	private static final long DEFAULT_TIMEOUT_MS = 60_000L; // 60s 自动停止

	private MediaPlayer mediaPlayer;
	private AudioManager audioManager;
	private AudioFocusRequest focusRequest;
	private Handler handler;

	@Override
	public void onCreate() {
		super.onCreate();
		audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
		handler = new Handler(Looper.getMainLooper());
		createNotificationChannel();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null && ACTION_STOP.equals(intent.getAction())) {
			stopSelf();
			return START_NOT_STICKY;
		}

		int alarmId = intent.getIntExtra("alarm_id", -1);
		String content = intent.getStringExtra("content");
		String audioFilename = intent.getStringExtra("audio_filename");
		long stopTimeoutMs = intent.getLongExtra(EXTRA_STOP_TIMEOUT_MS, DEFAULT_TIMEOUT_MS);

		// 停止按钮的 PendingIntent，点击即可停止前台服务与播放
		int flagsPi = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
		PendingIntent stopPendingIntent = PendingIntent.getService(
			this,
			0,
			new Intent(this, AlarmPlaybackService.class).setAction(ACTION_STOP),
			flagsPi
		);

		Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
				.setContentTitle("闹钟")
				.setContentText(content == null ? "闹钟响了" : content)
				.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
				.setPriority(NotificationCompat.PRIORITY_HIGH)
				.setOngoing(true)
				.addAction(new NotificationCompat.Action(
						android.R.drawable.ic_menu_close_clear_cancel,
						"停止",
						stopPendingIntent
				))
				.build();

		startForeground(1001, notif);

		// 自动超时停止，避免长时间在后台播放
		handler.removeCallbacksAndMessages(null);
		handler.postDelayed(this::stopSelf, stopTimeoutMs);

		// 请求音频焦点并开始播放
		requestAudioFocusAndPlay(audioFilename);

		return START_NOT_STICKY;
	}

	private void requestAudioFocusAndPlay(@Nullable String audioFilename) {
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				AudioAttributes attrs = new AudioAttributes.Builder()
						.setUsage(AudioAttributes.USAGE_ALARM)
						.setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
						.build();
				focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
						.setAudioAttributes(attrs)
						.setOnAudioFocusChangeListener(f -> {
							if (f == AudioManager.AUDIOFOCUS_LOSS) stopSelf();
							// 可选：处理短暂丢失焦点为暂停/恢复
							// else if (f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && mediaPlayer != null && mediaPlayer.isPlaying()) mediaPlayer.pause();
							// else if (f == AudioManager.AUDIOFOCUS_GAIN && mediaPlayer != null) try { mediaPlayer.start(); } catch (Exception ignored) {}
						})
						.build();
				audioManager.requestAudioFocus(focusRequest);
			} else {
				audioManager.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
			}

			if (audioFilename != null) {
				File f = new File(getExternalFilesDir("alarms"), audioFilename);
				if (f.exists()) {
					playUri(Uri.fromFile(f));
					return;
				}
			}

			// 回退到系统默认闹铃
			Uri defaultAlarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
			if (defaultAlarm == null) {
				defaultAlarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
			}
			playUri(defaultAlarm);

		} catch (Exception e) {
			Log.e(TAG, "请求音频焦点或播放失败", e);
			stopSelf();
		}
	}

	private void playUri(Uri uri) {
		try {
			mediaPlayer = new MediaPlayer();
			mediaPlayer.setDataSource(this, uri);
			mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
					.setUsage(AudioAttributes.USAGE_ALARM)
					.setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
					.build());
			mediaPlayer.setLooping(false);
			mediaPlayer.setOnCompletionListener(mp -> stopSelf());
			mediaPlayer.prepare();
			mediaPlayer.start();
		} catch (Exception e) {
			Log.e(TAG, "播放 Uri 失败", e);
			// 若播放失败，直接停止服务
			stopSelf();
		}
	}

	private void createNotificationChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "闹钟播放", NotificationManager.IMPORTANCE_HIGH);
			ch.setDescription("闹钟音频播放通知");
			NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
			if (nm != null) nm.createNotificationChannel(ch);
		}
	}

	@Override
	public void onDestroy() {
		if (mediaPlayer != null) {
			try {
				mediaPlayer.stop();
			} catch (Exception ignored) {}
			mediaPlayer.release();
			mediaPlayer = null;
		}

		if (handler != null) {
			handler.removeCallbacksAndMessages(null);
		}

		if (focusRequest != null && audioManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			audioManager.abandonAudioFocusRequest(focusRequest);
		} else if (audioManager != null) {
			audioManager.abandonAudioFocus(null);
		}

		stopForeground(true);
		super.onDestroy();
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
}
