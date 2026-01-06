package com.tongyi.multimodal_dialog;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.LinkedBlockingQueue;

public class AudioPlayer {

    public enum PlayState {
        idle,
        playing,
        pause,
        release
    }

    private static final String TAG = "AudioPlayer";

    private int SAMPLE_RATE = 24000;
    private  int outputLatency = 0;
    private boolean isFinishSend = false;
    private boolean isFirstAudio = true;  // TTS第一包数据
    private boolean isSilenceFlag = true;  // TTS首部是静音
    private int silenceBytes = 0;  // TTS首部静音字节数，只为了统计并显示
    private AudioPlayerCallback audioPlayerCallback;
    private LinkedBlockingQueue<byte[]> audioQueue = new LinkedBlockingQueue();
    private PlayState playState;
    private byte[] tempData;
    private Thread ttsPlayerThread;
    private int iMinBufSize = 0;
    private int iMinBufCount = 2;
    private AudioTrack audioTrack = null;

    AudioPlayer(AudioPlayerCallback callback, int sample_rate) {
        Log.i(TAG,"Audio Player init!");

        // 初始化播放器
        // 此处仅使用Android系统自带的AudioTrack进行音频播放Demo演示, 客户可根据自己需要替换播放器
        // 默认采样率为16000、单通道、16bit pcm格式
        iMinBufSize = AudioTrack.getMinBufferSize(sample_rate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT) * iMinBufCount;

        playState = PlayState.idle;
        if (audioTrack == null) {
            Log.i(TAG, "New AudioTrack with sample_rate:" + sample_rate + " iMinBufSize:" + iMinBufSize);
//            audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sample_rate,
//                    AudioFormat.CHANNEL_OUT_MONO,
//                    AudioFormat.ENCODING_PCM_16BIT,
//                    iMinBufSize, AudioTrack.MODE_STREAM);

//            audioTrack = new AudioTrack(
//                    new AudioAttributes.Builder()
//                            .setUsage(AudioAttributes.USAGE_MEDIA)
//                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
//                            .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
//                            .build(),
//                    new AudioFormat.Builder()
//                            .setSampleRate(sample_rate)
//                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
//                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
//                            .build(),
//                    iMinBufSize,
//                    AudioTrack.MODE_STREAM,
//                    AudioManager.AUDIO_SESSION_ID_GENERATE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioAttributes audioAttributes = new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build();
                AudioFormat format = new AudioFormat.Builder()
                        .setSampleRate(sample_rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build();

                audioTrack = new AudioTrack.Builder()
                        .setAudioAttributes(audioAttributes)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(iMinBufSize)
                        .build();
            } else {
                // 低于API 23的设备不支持设置performanceMode选项
                audioTrack = new AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        sample_rate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        iMinBufSize,
                        AudioTrack.MODE_STREAM
                );
            }
        }
        if (audioTrack == null) {
            Log.e(TAG, "AudioTrack new failed ...");
        }


        if (Build.VERSION.SDK_INT >= 18) {
            try {
                Method getLatencyMethod = AudioTrack.class.getMethod("getLatency", (Class < ? > []) null);
                outputLatency = (int) ((Integer) getLatencyMethod.invoke(audioTrack, (Object[]) null));
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                e.printStackTrace();
            }
        }


        audioTrack.play();
        audioPlayerCallback = callback;
        // 10 msec silence data for tts tail resample.
        ttsPlayerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

                while (playState != PlayState.release) {
                    if (playState == PlayState.playing) {
                        if (audioQueue.size() == 0) {
                            if (isFinishSend) {
                                audioPlayerCallback.playOver(false, outputLatency);
                                isFinishSend = false;
                                isFirstAudio = true;
                                isSilenceFlag = true;
                            } else {
                                try {
                                    Thread.sleep(10);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }
                            continue;
                        }

                        try {
                            tempData = audioQueue.take();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }

                        if (isFirstAudio) {
                            String show_text = "PlayFirstPackage with " + tempData.length + "bytes";
                            Log.d(TAG, show_text);
                            audioPlayerCallback.showLog(show_text, "");
                            isFirstAudio = false;
                        }

                        // 当前静音时, 持续计算音量，并去掉静音数据
                        if (isSilenceFlag) {
                            final int threshold = 55;
                            int start = 0;
                            int eleSize = iMinBufSize / iMinBufCount;
                            int totalChunks = tempData.length / eleSize + 1;
                            int currentSilenceBytes = 0; // 当前包的静音字节数
                            for (int i = 0; i < totalChunks; i++) {
                                int chunkSize = Math.min(tempData.length - start, eleSize);
                                if (chunkSize > 0) {
                                    byte[] chunk = new byte[chunkSize];
                                    System.arraycopy(tempData, start, chunk, 0, chunkSize);
                                    start += chunkSize;

                                    int eleMs = Utils.bytesToMs(chunkSize, SAMPLE_RATE);
                                    int tempSilenceBytes = calculateSilenceBytes(chunk, SAMPLE_RATE, threshold, eleMs);
                                    if (tempSilenceBytes > 0) {
                                        // 当前 ele_ms 是静音数据，则累计 silenceBytes，且不用写入AudioTrack
//                                        Log.d(TAG, tempSilenceBytes + "bytes silence data in player with " + chunkSize + "bytes chunk.");
                                        silenceBytes += tempSilenceBytes;
                                        currentSilenceBytes += tempSilenceBytes;
                                    } else {
                                        // 在此包中发现人声，则取出tempData中的人声写入AudioTrack
                                        int totalSilenceMs = Utils.bytesToMs(silenceBytes, SAMPLE_RATE);
                                        String showText = "PlayFirst with " + silenceBytes + "bytes(" + totalSilenceMs + ")ms静音";
                                        Log.d(TAG, showText);
                                        audioPlayerCallback.showLog(showText, "PlayFirst");
                                        isSilenceFlag = false;

                                        // 取当前包的人声写入AudioTrack
                                        int tempCurSilenceBytes = Math.min(tempData.length, currentSilenceBytes);
                                        byte[] voice = new byte[tempData.length - tempCurSilenceBytes];
                                        System.arraycopy(tempData, tempCurSilenceBytes, voice, 0, voice.length);
                                        int soundLevel = calculateRMSLevel(voice);
                                        audioPlayerCallback.playSoundLevel(soundLevel);
                                        audioPlayerCallback.onPlayerData(voice, voice.length);

                                        if (playState == PlayState.playing) {
                                            try {
                                                int audioTrackRt = audioTrack.write(voice, 0, voice.length);
                                                if (audioTrackRt < 0 ) {
                                                    Log.e(TAG, "audioTrack.write error:" + audioTrackRt);
                                                }
                                            }catch (Exception e) {
                                                e.printStackTrace();
                                            }
                                        }
                                        break;
                                    }
                                }
                            } // for
                        } else {
                            int soundLevel = calculateRMSLevel(tempData);
//                        Log.d(TAG, "sound_level: " + soundLevel);
                            audioPlayerCallback.playSoundLevel(soundLevel);
                            audioPlayerCallback.onPlayerData(tempData, tempData.length);
                            if (playState == PlayState.playing) {
                                try {
                                    int audioTrackRt = audioTrack.write(tempData, 0, tempData.length);
                                    if (audioTrackRt < 0 ) {
                                        Log.e(TAG, "audioTrack.write error:" + audioTrackRt);
                                    }
                                }catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        }

                    } else {
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        });
    }

    public void setAudioData(byte[] data) {
        if (playState != PlayState.playing) {
            return;
        }
        audioQueue.offer(data);
        //非阻塞
    }

    public void isFinishSend(boolean isFinish) {
        isFinishSend = isFinish;
        Log.i(TAG,"AudioPlayer isFinishSend:" + isFinishSend + " audioQueue.size:" + audioQueue.size() + " PlayState:" + playState);
    }

    public boolean checkFinish() {
        return isFinishSend;
    }

    public void play() {
        audioPlayerCallback.playStart();
        String show_text = "AudioTrack output latency: " + outputLatency + "ms.";
        audioPlayerCallback.showLog(show_text, "OutputLatency");

        if (!ttsPlayerThread.isAlive()) {
            ttsPlayerThread.setPriority(Thread.MAX_PRIORITY);
            ttsPlayerThread.start();
        }
        playState = PlayState.playing;
        Log.i(TAG,"AudioPlayer playState:" + playState);
        isFinishSend = false;
        if (audioTrack != null) {
            isFirstAudio = true;
            isSilenceFlag = true;
            audioTrack.setVolume(1.0f);
            audioTrack.play();
        }
    }

    public void tryPlay() {
        if (playState != PlayState.playing) {
            play();
        }
    }

    public void stop(boolean interrupt, boolean fadeout) {
        if (fadeout) {
            // 以一定的时间间隔逐步降低音量
            final int fadeOutDuration = 300; // 淡出持续时间，单位毫秒
            final int fadeOutStepTime = 5;  // 每一步淡出的时间间隔，单位毫秒
            final float maxVolume = 1.0f; // 初始音量，假设是最大音量
            final float minVolume = 0.0f; // 最终音量静音

            new Thread(new Runnable() {
                @Override
                public void run() {
                    // 开始播放音轨
                    audioTrack.play();
                    // 在这里添加你的音频写入逻辑

                    // 淡出计算
                    int fadeOutSteps = fadeOutDuration / fadeOutStepTime;
                    float volumeDecrementPerStep = maxVolume / fadeOutSteps;
                    float currentVolume = maxVolume;

                    // 淡出循环
                    Log.d(TAG, "feedout start.");
                    for (int step = 0; step < fadeOutSteps; step++) {
                        // 逐步递减音量
                        currentVolume -= volumeDecrementPerStep;
                        if (currentVolume < minVolume) currentVolume = minVolume;

                        // 在API 21及以上版本中使用setVolume方法
                        audioTrack.setVolume(currentVolume);

                        // 等待一会儿
                        try {
                            Thread.sleep(fadeOutStepTime);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                            break;
                        }

                        // 若音量已经降至最低，则停止播放并退出循环
                        if (currentVolume == minVolume) {
                            break;
                        }
                    }

                    playState = PlayState.idle;
                    Log.i(TAG, "stop-playState :" + playState);
                    audioQueue.clear();
                    if (audioTrack != null) {
                        //audioTrack.pause();
                        audioTrack.stop();
                        audioTrack.flush();
                    }
                    audioPlayerCallback.playOver(interrupt, outputLatency);
                    Log.d(TAG, "feedout end.");
                }
            }).start();
        } else {
            playState = PlayState.idle;
            Log.i(TAG,"stop-playState :" + playState);
            audioQueue.clear();
            if (audioTrack != null) {
                //audioTrack.pause();
                audioTrack.stop();
                audioTrack.flush();
            }
            audioPlayerCallback.playOver(interrupt, outputLatency);
        }
    }

    public void pause(boolean clear) {
        playState = PlayState.pause;
        if (clear) {
            audioQueue.clear();
        }
        if (audioTrack != null) {
            isFirstAudio = true;
            isSilenceFlag = true;
            audioTrack.pause();
        }
    }

    public void resume() {
        if (audioTrack != null) {
            if (audioQueue!=null && !audioQueue.isEmpty()) {
                audioQueue.clear();
            }
            isFirstAudio = true;
            isSilenceFlag = true;
            audioTrack.play();
        }
        playState = PlayState.playing;
    }

    public void restart() {
        stop(true, false);
        play();
    }

    public void initAudioTrack(int samplerate) {
        // 初始化播放器
        // 此处仅使用Android系统自带的AudioTrack进行音频播放Demo演示, 客户可根据自己需要替换播放器
        // 默认采样率为16000、单通道、16bit pcm格式
        SAMPLE_RATE = samplerate;
        iMinBufSize = AudioTrack.getMinBufferSize(samplerate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT) * iMinBufCount;
//        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, samplerate,
//                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
//                iMinBufSize, AudioTrack.MODE_STREAM);

//        audioTrack = new AudioTrack(
//                new AudioAttributes.Builder()
//                        .setUsage(AudioAttributes.USAGE_MEDIA)
//                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
//                        .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
//                        .build(),
//                new AudioFormat.Builder()
//                        .setSampleRate(samplerate)
//                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
//                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
//                        .build(),
//                iMinBufSize,
//                AudioTrack.MODE_STREAM,
//                AudioManager.AUDIO_SESSION_ID_GENERATE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build();

            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(iMinBufSize)
                    .build();
        } else {
            // 低于API 23的设备不支持设置performanceMode选项
            audioTrack = new AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    iMinBufSize,
                    AudioTrack.MODE_STREAM
            );
        }
    }

    public void releaseAudioTrack(boolean release) {
        if (audioTrack != null) {
            audioTrack.stop();
            if (release) {
                playState = PlayState.release;
            }
            audioTrack.release();
            Log.i(TAG,"releaseAudioTrack audioTrack released");
        }
        audioTrack = null;
    }

    public void setSampleRate(int sampleRate) {
        if (SAMPLE_RATE != sampleRate) {
            releaseAudioTrack(false);
            initAudioTrack(sampleRate);
            SAMPLE_RATE = sampleRate;
        }
    }

    // 计算给定PCM音频数据的RMS值
    private int calculateRMSLevel(byte[] audioData) {
        // 将byte数组转换为short数组（假设是16位PCM，小端序）
        short[] shorts = new short[audioData.length / 2];
        for (int i = 0; i < shorts.length; i++) {
            shorts[i] = (short) ((audioData[i * 2] & 0xFF) | (audioData[i * 2 + 1] << 8));
        }

        // 计算平均平方值
        double rms = 1.0;
        for (short s : shorts) {
            rms += (double)Math.abs(s);
        }
        rms = rms / shorts.length;

        // 计算分贝值
        double db = 20 * Math.log10(rms);
        db = db * 160 / 90 - 160;
        if (db > 0.0) {
            db = 0.0;
        } else if (db < -160.0) {
            db = -160;
        }

        // level值
        int level = (int)((db + 160) * 100 / 160);
        return level;
    }

    private int calculateSilenceBytes(byte[] audioData, int tts_sr, int threshold, int ele_ms) {
        int ele_size = Utils.msToBytes(ele_ms, tts_sr);
        int data_length = audioData.length;
        if (data_length >= ele_size) {
            int silence_bytes = 0;
            int total_chunks = data_length / ele_size + 1; // 计算块的总数，考虑到可能的最后一个不完整的块
            for (int i = 0; i < total_chunks; i++) {
                int start = i * ele_size; // 当前块的起始索引
                int end = Math.min(start + ele_size, data_length); // 当前块的结束索引

                byte[] chunk = new byte[end - start];
                System.arraycopy(audioData, start, chunk, 0, chunk.length);
                int level = calculateRMSLevel(chunk);
//                Log.d(TAG, "silence -> sound level:" + level + " threshold:" + threshold);
                if (level < threshold) {
                    silence_bytes += chunk.length;
                } else {
                    break;
                }
            }
            return silence_bytes;
        } else {
            return 0;
        }
    }
}
