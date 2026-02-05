package com.tongyi.multimodal_dialog;

import static com.tongyi.multimodal_dialog.MultiModalDialog.isRtcUseInternalAudio;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.alibaba.ty.conv.ConvConstants.DialogState;
import com.alibaba.ty.conv.ConvEvent;
import com.tongyi.multimodal_conversation.R;
import com.tongyi.multimodal_dialog.data.MultimodalParams;
import com.tongyi.multimodal_dialog.data.request.MultiModalRequestParam;
import com.tongyi.multimodal_dialog.utils.CameraManager;
import com.tongyi.multimodal_dialog.Constant.TYDebugInfoType;
import com.tongyi.multimodal_dialog.data.IDialogCallback;
import com.tongyi.multimodal_dialog.data.TYError;
import com.tongyi.multimodal_dialog.record.IRecorderCallback;
import com.tongyi.multimodal_dialog.record.TYAudioRecorder;
import com.tongyi.multimodal_dialog.util.DeviceUtil;
import com.tongyi.multimodal_dialog.util.ThreadPoolUtil;
import com.tongyi.multimodal_dialog.utils.NetworkMp3Player;
import com.tongyi.multimodal_dialog.util.AlarmScheduler;
import com.tongyi.multimodal_dialog.util.VolumeController;
import com.tongyi.multimodal_dialog.Utils;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Locale;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import android.util.Base64;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import android.os.Looper;

import android.view.View;
import android.view.SurfaceView;
import android.graphics.SurfaceTexture;

/**
 * 多模态对话Activity
 */
public class MultimodalConversationActivity extends AppCompatActivity {
    private static final String TAG = "MultimodalConversation";
    public static final String KEY_AUTH_PARAM = "KEY_AUTH_PARAM";

    // UI组件
    private TextView tvLogs;
    private TextView tvState;
    private ViewGroup btnInterrupt;
    private ViewGroup btnExit;
    private TextView tvInterruptText;
    private FrameLayout live2dContainer;
    private WebView live2dWebView;
    private FrameLayout videoContainer;
    private TextureView cameraTextureView;
    private ScrollView scrollLogs;
    private Handler uiHandler;

    // 防抖：频繁 setText（逐字流式）时合并滚动请求，避免滚动抖动
    private static final long SCROLL_TO_BOTTOM_DEBOUNCE_MS = 16;
    @Nullable
    private Runnable scrollToBottomRunnable;

    private View mainContent;
    private int mainContentPaddingLeft;
    private int mainContentPaddingTop;
    private int mainContentPaddingRight;
    private int mainContentPaddingBottom;

    // 音乐播放控件
    private LinearLayout musicPlayerContainer;
    private TextView tvMusicInfo;
    private ViewGroup btnStopMusic;
    private ProgressBar progressMusic;

    // 核心组件
    private MultiModalDialog multiModalDialog;
    private MultimodalParams authParams;
    private TYAudioRecorder audioRecorder;
    private AudioPlayer audioPlayer;

    private NetworkMp3Player networkMp3Player;

    // 状态变量
    private volatile DialogState currentState = DialogState.DIALOG_IDLE;
    private boolean isVqaMode = false;
    private boolean isVideoMode = false; // 是否处于视频模式
    private boolean isExecutingCommand = false;
    private String dialogId;
    private String taskId;

    // 性能监控
    private long initStartTime;
    private long initEndTime;
    private long connectCost = 0;

    private boolean enableKeywordSpotting = true;
    private boolean lastAsrFinished = false; // 添加这个标志位来跟踪上一次ASR是否已完成

    public static boolean vqaUseUrl = true; // VQA命令中是否使用图片URL


    // 视频帧流发送线程及停止控制
    private Thread videoStreamingThread;
    private volatile boolean videoStreamingRunning = false;
    // 唤醒词列表
    private final JSONArray kwsWords = new JSONArray();

    /////////////////////////////////////// 启动相关 ///////////////////////////////////////

    public static void launch(AppCompatActivity activity, MultimodalParams authParams) {
        Intent intent = new Intent(activity, MultimodalConversationActivity.class);
        intent.putExtra(KEY_AUTH_PARAM, authParams);
        activity.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initializeActivity();
        initializeUI();
        initializeSDK();
    }

    private void initializeActivity() {
        DeviceUtil.setStatusBarColor(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        authParams = (MultimodalParams) getIntent().getSerializableExtra(KEY_AUTH_PARAM);
        if (authParams == null) {
            Log.e(TAG, "Auth params is null");
            finish();
            return;
        }

        setContentView(R.layout.activity_voice_chat);
        uiHandler = new Handler(Looper.getMainLooper());
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initializeUI() {
        // 绑定UI组件
        tvLogs = findViewById(R.id.tv_logs);
        tvState = findViewById(R.id.tv_state);
        btnInterrupt = findViewById(R.id.btn_interrupt);
        btnExit = findViewById(R.id.btn_exit);
        tvInterruptText = findViewById(R.id.tv_interrupt_text);
        live2dContainer = findViewById(R.id.live2d_container);
        live2dWebView = findViewById(R.id.live2d_webview);
        if (live2dWebView != null) {
            live2dWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }
        videoContainer = findViewById(R.id.video_container);
        mainContent = findViewById(R.id.main_content);
        scrollLogs = findViewById(R.id.scroll_logs);
        musicPlayerContainer = findViewById(R.id.music_player_container);
        tvMusicInfo = findViewById(R.id.tv_music_info);
        btnStopMusic = findViewById(R.id.btn_stop_music);
        progressMusic = findViewById(R.id.progress_music);

        // 任何导致日志区域高度变化的布局过程结束后，自动贴底
        tvLogs.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                requestScrollToBottom());

        // 初始化滚动 runnable（使用字段，便于 removeCallbacks 做防抖）
        scrollToBottomRunnable = () -> scrollLogs.post(() -> {
            View child = scrollLogs.getChildAt(0);
            if (child != null) {
                int viewportHeight = scrollLogs.getHeight() - scrollLogs.getPaddingTop() - scrollLogs.getPaddingBottom();
                // 额外留一点余量，避免最后一行因为 lineSpacing/padding 显示不全
                int bottomSlack = Math.max(1, tvLogs.getLineHeight() / 4);
                int targetY = Math.max(0, child.getHeight() - viewportHeight + bottomSlack);
                scrollLogs.scrollTo(0, targetY);
            } else {
                scrollLogs.fullScroll(View.FOCUS_DOWN);
            }
        });

        // 缓存 main_content 的原始 padding，便于视频模式时增加“安全边距”
        mainContentPaddingLeft = mainContent.getPaddingLeft();
        mainContentPaddingTop = mainContent.getPaddingTop();
        mainContentPaddingRight = mainContent.getPaddingRight();
        mainContentPaddingBottom = mainContent.getPaddingBottom();

        setupLive2DWebView();

        // 设置点击事件
        setupClickListeners();

        // 初始状态
        updateUIState("准备中...");
        btnInterrupt.setVisibility(View.INVISIBLE);
    }

    private void setupClickListeners() {
        // 打断/说话按钮
        btnInterrupt.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        handleInterruptDown();
                        break;
                    case MotionEvent.ACTION_UP:
                        handleInterruptUp();
                        break;
                }
                return true;
            }
        });

        // 退出按钮
        btnExit.setOnClickListener(v -> {
            showToast("正在退出...");
            finish();
        });

        // 停止音乐按钮
        btnStopMusic.setOnClickListener(v -> {
            if (networkMp3Player.isPlaying()) {
                networkMp3Player.stop();
                updateMusicPlayerUI(false);
                isExecutingCommand = false;
                runOnUiThread(()->updateStateUI(currentState));
                multiModalDialog.requestToRespond("transcript", "已停止播放音乐。", null);
            }
        });
    }

    private void initializeSDK() {
        initStartTime = SystemClock.uptimeMillis();

        // 初始化音频录制器
        audioRecorder = new TYAudioRecorder("", this, audioRecorderCallback, 7);
        networkMp3Player = new NetworkMp3Player();

        // 初始化音频播放器
        audioPlayer = new AudioPlayer(getAudioPlayerCallback(), 48000);

        // 初始化相机管理器
        CameraManager.getInstance().init(this, 1280, 720);

        // 初始化多模态对话
        try {
            initializeMultiModalDialog();
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }

        initEndTime = SystemClock.uptimeMillis();
        Log.d(TAG, "SDK初始化耗时: " + (initEndTime - initStartTime) + "ms");

        // 启动对话
        startConversation();
    }

    private void initializeMultiModalDialog() throws JSONException {
        MultiModalDialog.wsUseInternalAEC = true;
        multiModalDialog = new MultiModalDialog(
                this,
                authParams.getUrl(),
                authParams.getChainMode(),
                authParams.getWorkspaceId(),
                authParams.getAppid(),
                authParams.getDialogMode()
        );
        if (!TextUtils.isEmpty(authParams.getModelId())) {
            multiModalDialog.setModel(authParams.getModelId());
        }

        multiModalDialog.setDialogTimeout(10 * 1000);

        /*
        * 添加自定义唤醒词
        * */
        JSONObject wakeWord1 = new JSONObject();
        wakeWord1.put("name", "小云小云");
        wakeWord1.put("type", "main");
        kwsWords.put(wakeWord1);
        // 启用唤醒，默认唤醒词为"小云小云"
        if (enableKeywordSpotting){
            //如果开启唤醒，那么需要先启动录音
            MultiModalDialog.wsUseInternalVAD = true;
            multiModalDialog.enableKWS(true, false, kwsWords);
        }
        multiModalDialog.createConversation(buildRequestParams(), dialogCallback);
    }

    private void startConversation() {
        if (!multiModalDialog.isPush2TalkMode()) {
            ThreadPoolUtil.runOnSubThread(() -> audioRecorder.resume());
        }

        updateUIState("连接中...");
        multiModalDialog.start(Objects.requireNonNull(authParams.getApiKey()), "");
    }

    /////////////////////////////////////// 对话回调 ///////////////////////////////////////

    private final IDialogCallback dialogCallback = new IDialogCallback() {

        @Override
        public void onStartResult(boolean isSuccess, TYError errorInfo) {
            Log.d(TAG, "连接结果: " + isSuccess + ", 错误: " + errorInfo);

            if (isSuccess) {
                connectCost = SystemClock.uptimeMillis() - initEndTime;
                appendLogMessage("连接成功，耗时: " + connectCost + "ms");
                runOnUiThread(() -> btnInterrupt.setVisibility(View.VISIBLE));
            } else {
                appendLogMessage("连接失败: " + errorInfo);
            }
        }

        @Override
        public void onConvStateChangedCallback(@NonNull DialogState state) {
            currentState = state;
            runOnUiThread(() -> updateStateUI(currentState));
        }

        @Override
        public void onConvEventCallback(@Nullable ConvEvent event) {
            if (event == null) return;

            dialogId = event.getDialogId();
            taskId = event.getTaskId();

            handleConversationEvent(event);
        }

        @Override
        public void onSynthesizedSpeech(@NonNull byte[] bytes) {
            audioPlayer.setAudioData(bytes);
        }

        @Override
        public void onErrorReceived(@NonNull TYError errorInfo) {
            Log.e(TAG, "收到错误: " + errorInfo);
            appendLogMessage("错误: " + errorInfo.getMessage());
        }

        @Override
        public void onKeyWordSpotted(@NonNull String word, @NonNull Constant.KeyWordsType type) {
            showToast("唤醒: " + word);
        }

        @Override
        public void onSpeechTimeout(long timeout) {
            Log.d(TAG, "语音超时: " + timeout);
            // 语音播报
            //multiModalDialog.requestToRespond("transcript", "我先离开哦，有事再找我！", null);
        }

        @Override
        public void onInterruptResult(boolean isSuccess, @Nullable TYError errorInfo) {
            Log.d(TAG, "打断结果: " + isSuccess);
            if (isSuccess) {
                audioPlayer.stop(true, false);
            }
        }

        @Override
        public void onReadyToSpeech() {
            // 准备说话
        }

        @Override
        public void onConvSoundLevelCallback(float audioLevel, @NonNull Constant.TYVolumeSourceType audioType) {
            // 音量回调
        }

        @Override
        public void onGotRenderView(@NonNull android.view.SurfaceView renderView) {
            // 视频渲染
        }

        @Override
        public void onDebugInfoTrack(int level, @NonNull TYDebugInfoType debugInfoType, @NonNull String debugInfo) {
            // 调试信息
        }

        @Override
        public void onPlaybackAudioData(@NonNull byte[] bytes) {
            // 播放音频数据
        }
    };

    /////////////////////////////////////// 事件处理 ///////////////////////////////////////

    private void handleConversationEvent(ConvEvent event) {
        switch (event.getEventType()) {
            case EVENT_CONVERSATION_STARTED:
                handleConversationStarted();
                break;
            case EVENT_HUMAN_SPEAKING_DETAIL:
                //Log.d(TAG, "收到人类说话详情");
                handleSpeakingDetail(event.getResponse(), true);
                break;
            case EVENT_RESPONDING_DETAIL:
                handleSpeakingDetail(event.getResponse(), false);
                handleResponseCommand(event.getResponse());
                break;
            case EVENT_SENTENCE_BEGIN:
                Log.d(TAG, "开始说话");
                break;
            case EVENT_SENTENCE_END:
                handleSentenceEnd();
                break;
            case EVENT_DATA_OUTPUT_STARTED:
                handleOutputStarted();
                break;
            case EVENT_DATA_OUTPUT_COMPLETED:
                handleOutputCompleted();
                break;
        }
    }

    private void handleConversationStarted() {
        long readyTime = SystemClock.uptimeMillis() - initEndTime - connectCost;
        appendLogMessage("对话准备完成，耗时: " + readyTime + "ms");

        runOnUiThread(() -> updateUIState("请开始说话"));
        runOnUiThread(this::showLive2DCharacter);

        if (multiModalDialog.isPush2TalkMode()) {
            runOnUiThread(() -> btnInterrupt.setVisibility(View.VISIBLE));
        }

        // 启动视频模式
        //startVideoMode();
        Log.d(TAG, "当前链路模式: " + authParams.getChainMode());
        if (authParams.getChainMode() == Constant.ChainMode.RTC) {
            startVideoMode();
        }
    }

    private void handleSpeakingDetail(String response, boolean isHuman) {
        Pair<String, Boolean> detail = parseTextDetail(response);
        if (detail != null) {
            String text = detail.first;
            boolean finished = detail.second;
            String messageFrom = isHuman ? "我" : "AI";

            if (isHuman) {
                // 处理ASR结果的流式显示
                handleAsrStreamingDisplay(text, finished, messageFrom);
            } else {
                // AI回复直接显示，但过滤空内容
                if (text != null && !text.trim().isEmpty()) {
                    appendLogMessage(messageFrom + ": " + text);
                }else{
                    // 请求AI进行回复
                    //multiModalDialog.requestToRespond("transcript", "请继续说你的要求吧!", null);
                    return;
                }
            }
        }
    }

    /**
     * 处理ASR结果的流式显示
     * @param text ASR识别文本
     * @param finished 是否为最终结果
     * @param messageFrom 消息来源
     */
    private void handleAsrStreamingDisplay(String text, boolean finished, String messageFrom) {
        runOnUiThread(() -> {
            // 中间结果和最终结果都在同一行显示
            String displayText = messageFrom + ": " + text + (finished ? "" : "...");

            String currentText = tvLogs.getText().toString();
            String newText;

            if (currentText.equals("等待对话开始...")) {
                // 第一次显示
                newText = displayText;
                lastAsrFinished = finished; // 更新标志位
            } else {
                String[] lines = currentText.split("\n");
                // 检查是否应该新起一行：
                // 1. 上一次的ASR已经完成
                // 2. 或者最后一行不是ASR结果（不以"我: "开头）
                // 3. 或者最后一行是已完成的ASR结果（以"我: "开头且不以"..."结尾）
                boolean shouldStartNewLine = lastAsrFinished ||
                        (lines.length > 0 && !lines[lines.length - 1].startsWith(messageFrom + ": ")) ||
                        (lines.length > 0 && lines[lines.length - 1].startsWith(messageFrom + ": ") &&
                                !lines[lines.length - 1].endsWith("..."));

                if (!shouldStartNewLine && lines.length > 0 && lines[lines.length - 1].startsWith(messageFrom + ": ")) {
                    // 更新最后一行（同一轮对话的ASR结果）
                    StringBuilder updatedText = new StringBuilder();
                    for (int i = 0; i < lines.length - 1; i++) {
                        updatedText.append(lines[i]).append("\n");
                    }
                    updatedText.append(displayText);
                    newText = updatedText.toString();
                } else {
                    // 新起一行（新一轮对话或上一轮已完成）
                    newText = currentText + "\n" + displayText;
                }

                // 更新标志位
                lastAsrFinished = finished;
            }

            setLogsTextStyled(newText);
            requestScrollToBottom();
        });
    }

    private void handleResponseCommand(String response) {
        String command = parseResponseCommand(response);
        if (command != null) {
            isExecutingCommand = true;
            ThreadPoolUtil.runOnSubThread(() -> executeCommand(command));
        }
    }

    private void handleSentenceEnd() {
        if (!multiModalDialog.isDuplexMode()) {
            Log.d(TAG, "暂停录音");
            if (authParams.getChainMode() != Constant.ChainMode.RTC && !enableKeywordSpotting) {
                audioRecorder.pause();
            }
        }
    }

    private void handleOutputStarted() {
        audioPlayer.pause(true);
        audioPlayer.play();

        if (authParams.getChainMode() == Constant.ChainMode.RTC) {
            //RTC 模式下，tts 合成数据速度为正常比例
            multiModalDialog.sendResponseStarted();
        }
    }

    private void handleOutputCompleted() {
        Log.d(TAG, "输出完成");
        audioPlayer.isFinishSend(true);
        if (authParams.getChainMode() == Constant.ChainMode.RTC) {
            multiModalDialog.sendResponseEnded();
            audioPlayer.isFinishSend(true);
        }
    }

    /////////////////////////////////////// UI更新 ///////////////////////////////////////

    private void updateStateUI(DialogState state) {
        String stateMsg;
        boolean showInterrupt = false;

        switch (state) {
            case DIALOG_IDLE:
                stateMsg = "空闲";
                break;
            case DIALOG_LISTENING:
                if (!isExecutingCommand) {
                    stateMsg = "🎤 你说，我正在听";
                    if (authParams.getChainMode() != Constant.ChainMode.RTC) {
                        audioRecorder.resume();
                    }
                } else {
                    stateMsg = "处理中...";
                }
                break;
            case DIALOG_RESPONDING:
                stateMsg = multiModalDialog.isDuplexMode() ?
                        "🔊 我正在说，可随时打断" : "🔊 我正在说，暂不支持语音打断";
                showInterrupt = !multiModalDialog.isDuplexMode();
                break;
            case DIALOG_THINKING:
                stateMsg = "🤔 我正在想";
                break;
            default:
                stateMsg = "未知状态";
                break;
        }

        updateUIState(stateMsg);

        if (multiModalDialog.isPush2TalkMode()) {
            btnInterrupt.setVisibility(View.VISIBLE);
            tvInterruptText.setText((state == DialogState.DIALOG_IDLE || state == DialogState.DIALOG_LISTENING) ? "按住说话 \n松开结束" : "点击打断");
        } else {
            btnInterrupt.setVisibility(showInterrupt ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void updateUIState(String message) {
        runOnUiThread(() -> tvState.setText(message));
    }

    private void setupLive2DWebView() {
        if (live2dWebView == null) {
            return;
        }
        WebSettings settings = live2dWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);
        live2dWebView.setBackgroundColor(Color.TRANSPARENT);
        live2dWebView.setWebViewClient(new WebViewClient());
        live2dWebView.setWebChromeClient(new WebChromeClient(){
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
                Log.d(TAG, "Live2D WebView: " + consoleMessage.message() + " @" + consoleMessage.sourceId() + ":" + consoleMessage.lineNumber());
                return super.onConsoleMessage(consoleMessage);
            }
        });
        WebView.setWebContentsDebuggingEnabled(true);
        // 预先加载，进入对话后直接显示
        live2dWebView.loadUrl("file:///android_asset/live2d/live2d.html");
    }

    private void showLive2DCharacter() {
        if (live2dContainer == null || live2dWebView == null) {
            return;
        }
        live2dContainer.setVisibility(View.VISIBLE);
        live2dWebView.onResume();
    }

    private void appendLogMessage(String message) {
        runOnUiThread(() -> {
            String currentText = tvLogs.getText().toString();
            String newText = currentText.equals("等待对话开始...") ? message :
                    currentText + "\n" + message;
            setLogsTextStyled(newText);
            requestScrollToBottom();
        });
    }

    private void setLogsTextStyled(@NonNull String rawText) {
        // 我：浅色；AI：深色；系统提示（连接成功等）：浅色
        int userColor = ContextCompat.getColor(this, R.color.text_secondary);
        int aiColor = ContextCompat.getColor(this, R.color.text_primary);
        int systemColor = ContextCompat.getColor(this, R.color.text_secondary);

        SpannableStringBuilder ssb = new SpannableStringBuilder(rawText);
        int length = rawText.length();
        int lineStart = 0;

        while (lineStart <= length) {
            int lineEnd = rawText.indexOf('\n', lineStart);
            if (lineEnd == -1) {
                lineEnd = length;
            }

            if (lineEnd > lineStart) {
                String line = rawText.substring(lineStart, lineEnd);
                int color = systemColor;
                if (line.startsWith("我:")) {
                    color = userColor;
                } else if (line.startsWith("AI:")) {
                    color = aiColor;
                }
                ssb.setSpan(new ForegroundColorSpan(color), lineStart, lineEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            if (lineEnd >= length) {
                break;
            }
            lineStart = lineEnd + 1;
        }

        tvLogs.setText(ssb);
    }

    private void scrollToBottom() {
        requestScrollToBottom();
    }

    private void requestScrollToBottom() {
        if (uiHandler == null || scrollLogs == null) {
            return;
        }
        if (scrollToBottomRunnable == null) {
            // initializeUI() 之前的兜底（正常不会走到这里）
            scrollToBottomRunnable = () -> scrollLogs.post(() -> scrollLogs.fullScroll(View.FOCUS_DOWN));
        }
        uiHandler.removeCallbacks(scrollToBottomRunnable);
        uiHandler.postDelayed(scrollToBottomRunnable, SCROLL_TO_BOTTOM_DEBOUNCE_MS);
    }

    /////////////////////////////////////// 交互处理 ///////////////////////////////////////

    private void handleInterruptDown() {
        if (!multiModalDialog.isDuplexMode()) {
            if (currentState != DialogState.DIALOG_IDLE && currentState != DialogState.DIALOG_LISTENING) {
                Log.d(TAG, "执行打断");
                audioPlayer.stop(true, false);
                multiModalDialog.interrupt();
            }else if (multiModalDialog.isPush2TalkMode()) {
                //push2talk 模式下，按住说话
                multiModalDialog.startSpeech();
            }
        }
    }

    private void handleInterruptUp() {
        if (multiModalDialog.isPush2TalkMode()) {
            //push2talk 模式下，松开结束
            multiModalDialog.stopSpeech();
        }
    }

    /////////////////////////////////////// 命令执行 ///////////////////////////////////////

    private void executeCommand(String command) {
        Log.d(TAG, "执行命令: " + command);

        try {
            JSONObject commandObj =  new JSONArray(command).getJSONObject(0);
            if (commandObj.has("name")) {
                // multimodal app response
                String cmdName = commandObj.getString("name");
                switch (cmdName) {
                    case "visual_qa":
                        executeVQACommand();
                        break;
                    case "quit_videochat":
                        stopVideoMode();
                        break;
                    case "increase_volume":
                    case "increase_volume_default": {
                        // 新格式：[{"intent_info":{"domain":"general_command","intent":"increase_volume_default"},"name":"increase_volume_default","params":[{"name":"for","value":"系统","normValue":"系统"}]}]
                        // 固定步进增加音量
                        final int step = 3;
                        handle_volume_command(step, commandObj);
                        break;
                    }
                    case "decrease_volume":
                    case "decrease_volume_default": {
                        // 新格式：intent decrease_volume_default
                        final int step = -3;
                        handle_volume_command(step, commandObj);
                        break;
                    }
                    case "play_music":
                        handleMusicRadioCommand(commandObj);
                        break;
                    case "music_radio":
                        handleMusicRadioCommand(commandObj);
                        break;
                    case "SET_reminder":
                        handle_setreminder_multimodel(commandObj);
                        break;
                    case "open_videochat":
                        startVideoMode();
                        break;
                    default:
                        executeDefaultCommand();
                        break;
                }
            }else if (commandObj.has("id")) {
                // voice app response
                String cmdId = commandObj.getString("id");
                switch (cmdId) {
                    case "music_radio":
                        handleMusicRadioCommand(commandObj);
                        break;
                    case "tell_story":
                        // executeDefaultCommand();
                        Log.d(TAG, "current state" + currentState);
                        break;
                    default:
                        break;
                }
            } else if (commandObj.has("function")){
                // 音量控制功能
                JSONObject functionObj = commandObj.getJSONObject("function");
                String functionName = functionObj.getString("name");
                switch (functionName){
                    case "INCREASE_DEFAULT_volume": {
                        // 固定增加音量
                        final int step = 3;
                        handle_volume_command(step, functionObj);
                        // 向服务器发送命令执行完成的反馈
                        //multiModalDialog.requestToRespond("transcript", "音量已增加到 " + cur + "/" + max, null);
                        break;
                    }
                    case "DECREASE_DEFAULT_volume": {
                        // 固定减少音量
                        final int step = -3;
                        handle_volume_command(step, functionObj);
                        // 向服务器发送命令执行完成的反馈
                        //multiModalDialog.requestToRespond("transcript", "音量已减少到 " + cur + "/" + max, null);
                        break;
                    }
                    case "SET_clock": {
                        // 设置闹钟功能
                        //Log.d(TAG, "function pack: " + functionObj.toString());
                        // 此处添加设置闹钟的具体实现
                        handle_setClock(functionObj);
                        // 更改运行状态
                        isExecutingCommand = false;
                        runOnUiThread(()->updateStateUI(currentState));
                        // 向服务器发送命令执行完成的反馈
                        multiModalDialog.requestToRespond("transcript", "闹钟设置完成", null);
                        break;
                    }
                    case "SET_reminder": {
                        // 相对时间提醒功能（如：1分钟后提醒、10秒后提醒）
                        Log.d(TAG, "收到相对时间提醒命令: " + functionObj.toString());
                        handle_setReminder(functionObj);
                        // 更改运行状态
                        isExecutingCommand = false;
                        runOnUiThread(()->updateStateUI(currentState));
                        // 向服务器发送命令执行完成的反馈
                        multiModalDialog.requestToRespond("transcript", "提醒设置完成", null);
                        break;
                    }
                    default:
                        break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "命令执行失败", e);
            isExecutingCommand = false;
        }
    }

    /*
    音量控制命令处理
    */
    private void handle_volume_command(int step, JSONObject commandObj){
        VolumeController.adjustByStep(this, step);
        int cur = VolumeController.getCurrent(this);
        int max = VolumeController.getMax(this);
        if (step > 0){
             Log.d(TAG, "音量增加(新格式)(" + step + "): " + cur + "/" + max + "); params=" + (commandObj.has("params") ? commandObj.optJSONArray("params") : null));
        }else{
            Log.d(TAG, "音量减少(新格式)(" + (-step) + "): " + cur + "/" + max + "; params=" + (commandObj.has("params") ? commandObj.optJSONArray("params") : null));
        }
        runOnUiThread(() -> Toast.makeText(this, "音量已减少" + (-step) + "(" + cur + "/" + max + ")", Toast.LENGTH_SHORT).show());
        isExecutingCommand = false;
        runOnUiThread(() -> updateStateUI(currentState));
        //multiModalDialog.requestToRespond("transcript", "音量已减少到 " + cur + "/" + max, null);
    }

    /*
    handle_setreminder_multimodel模式下的相对时间提醒功能：
    不同模式下包的格式不同。
    */
    private void handle_setreminder_multimodel(JSONObject commandObj) {
        try {
            // check parameters existence
            String duration = "";
            String content = "";
            String paramsArrStr = commandObj.getString("params");
            JSONArray paramsStr = new JSONArray(paramsArrStr);
            for (int i = 0;i<paramsStr.length();i++){
                JSONObject p = paramsStr.optJSONObject(i);
                if (p == null) continue;
                if (p.has("name") && p.getString("name").equals("duration")){
                    duration = p.getString("value");
                }
                if (p.has("name")&&p.getString("name").equals("content")){
                    content = p.getString("value");
                }
            }
            // JSONObject argumentsObj = new JSONObject(paramsStr);

            // if (argumentsObj.has("duration")) {
            //     duration = argumentsObj.getString("duration");
            // }
            // if(argumentsObj.has("content")){
            //     content = argumentsObj.getString("content");
            // }
            // 这里可以添加设置提醒的具体实现代码
            Log.d(TAG, "设置提醒: 持续时间=" + duration + ", 内容=" + content);
            // 解析时长并设置提醒
            long delayMillis = parseDuration(duration);
            if (delayMillis > 0) {
                // 计算目标时间
                long triggerTime = System.currentTimeMillis() + delayMillis;

                // 生成唯一ID
                int alarmId = Utils.generateAlarmId(duration, "", content, "");

                // 设置提醒
                boolean success = AlarmScheduler.scheduleOneShot(this, alarmId, content, triggerTime);

                if (success) {
                    String message = String.format("已设置%s后提醒：%s", duration, content);
                    Log.d(TAG, message);
                    runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
                    // 更改运行状态
                    isExecutingCommand = false;
                    runOnUiThread(() -> updateStateUI(currentState));
                    multiModalDialog.requestToRespond("transcript", "设置成功", null);
                } else {
                    Log.e(TAG, "设置提醒失败");
                    runOnUiThread(() -> Toast.makeText(this, "设置提醒失败", Toast.LENGTH_SHORT).show());
                }
            } else {
                Log.e(TAG, "无法解析时长: " + duration);
                runOnUiThread(() -> Toast.makeText(this, "无法识别时间：", Toast.LENGTH_SHORT).show());
            }

        } catch (JSONException e) {
            Log.e(TAG, "解析提醒参数失败", e);
            runOnUiThread(() -> Toast.makeText(this, "提醒参数解析失败", Toast.LENGTH_SHORT).show());
        }
    }


    /*
    handle_setClock: 处理设置闹钟的功能实现
    functionObj: 包含闹钟设置参数的JSON对象
    调用AlarmScheduler工具类来调度闹钟
     */
    private void handle_setClock(JSONObject functionObj) {
        // 解析闹钟设置参数
        try {
            String argumentsStr = functionObj.getString("arguments");
            JSONObject argumentsObj = new JSONObject(argumentsStr);
            // check parameters existence
            String time = "";
            String date = "";
            String content = "";
            String repeat = "";
            if (argumentsObj.has("time")) {
                time = argumentsObj.getString("time");
            }
            if(argumentsObj.has("date")){
                date = argumentsObj.getString("date");
            }
            if(argumentsObj.has("content")){
                content = argumentsObj.getString("content");
            }
            if(argumentsObj.has("repeat")){
                repeat = argumentsObj.getString("repeat");
            }
            // 这里可以添加设置闹钟的具体实现代码
            Log.d(TAG, "设置闹钟: 时间=" + time + ", 日期=" + date + ", 内容=" + content + ", 重复=" + repeat);
            // 生成独特的闹钟id
            int alarmId = Utils.generateAlarmId(time, date, content, repeat);
            //Log.d(TAG, "生成的闹钟ID: " + alarmId);
            // 使用封装的调度工具
            // TODO: 这一部分代码仅用于测试，后续需要修改
            long triggerAtMillis = AlarmScheduler.parseTriggerTimeMillis(date, time);
            Log.d(TAG, "生成闹钟触发时间戳: " + triggerAtMillis);
            boolean ok = AlarmScheduler.scheduleOneShot(this, alarmId, content, triggerAtMillis);
            if (ok) {
                runOnUiThread(() -> Toast.makeText(this, "闹钟已设置", Toast.LENGTH_SHORT).show());
            } else {
                runOnUiThread(() -> Toast.makeText(this, "无法设置闹钟", Toast.LENGTH_SHORT).show());
            }

        } catch (JSONException e) {
            Log.e(TAG, "解析闹钟参数失败", e);
        }
    }

    /*
    handle_setReminder: 处理相对时间提醒功能
    functionObj: 包含提醒参数的JSON对象，如："1分钟后提醒我开会"
     */
    private void handle_setReminder(JSONObject functionObj) {
        try {
            // 从实际JSON包格式解析：{"arguments":"{\"duration\": \"分钟\", \"content\": \"喝水提醒\"}","name":"SET_reminder"}
            String argumentsStr = functionObj.getString("arguments");
            Log.d(TAG, "原始arguments字符串: " + argumentsStr);
            
            JSONObject argumentsObj = new JSONObject(argumentsStr);
            
            // 解析参数
            String duration = "";  // 相对时间，如："1分钟"、"30秒"
            String content = "提醒"; // 提醒内容
            
            if (argumentsObj.has("duration")) {
                duration = argumentsObj.getString("duration");
            }
            if (argumentsObj.has("content")) {
                content = argumentsObj.getString("content");
            }
            
            Log.d(TAG, "解析结果 - 持续时间: '" + duration + "', 内容: '" + content + "'");
            
            // 解析时长并设置提醒
            long delayMillis = parseDuration(duration);
            if (delayMillis > 0) {
                // 计算目标时间
                long triggerTime = System.currentTimeMillis() + delayMillis;
                
                // 生成唯一ID
                int alarmId = Utils.generateAlarmId(duration, "", content, "");
                
                // 设置提醒
                boolean success = AlarmScheduler.scheduleOneShot(this, alarmId, content, triggerTime);
                
                if (success) {
                    String message = String.format("已设置%s后提醒：%s", duration, content);
                    Log.d(TAG, message);
                    runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
                } else {
                    Log.e(TAG, "设置提醒失败");
                    runOnUiThread(() -> Toast.makeText(this, "设置提醒失败", Toast.LENGTH_SHORT).show());
                }
            } else {
                Log.e(TAG, "无法解析时长: " + duration);
                runOnUiThread(() -> Toast.makeText(this, "无法识别时间：", Toast.LENGTH_SHORT).show());
            }
            
        } catch (JSONException e) {
            Log.e(TAG, "解析提醒参数失败", e);
            runOnUiThread(() -> Toast.makeText(this, "提醒参数解析失败", Toast.LENGTH_SHORT).show());
        }
    }
    
    // 解析相对时间（如："1分钟"、"30秒"、"2小时"等）
    private long parseDuration(String duration) {
        try {
            Log.d(TAG, "解析时长字符串: " + duration);

            if (duration == null || duration.trim().isEmpty()) {
                return 0;
            }

            duration = duration.trim();

            // 优先匹配阿拉伯数字 + 单位
            Pattern patternDigits = Pattern.compile("(\\d+)\\s*(秒(钟)?|分钟|分|小时|时)");
            Matcher mDigits = patternDigits.matcher(duration);
            if (mDigits.find()) {
                int number = Integer.parseInt(mDigits.group(1));
                String unit = mDigits.group(2);
                Log.d(TAG, "解析到数字: " + number + ", 单位: " + unit);
                if (unit.startsWith("秒")) return number * 1000L;
                if (unit.startsWith("分钟") || unit.equals("分")) return number * 60 * 1000L;
                return number * 60 * 60 * 1000L;
            }

            // 支持中文数字（例如："十秒"、"三分钟"、"两小时"）
            Pattern patternChinese = Pattern.compile("([零一二两三四五六七八九十百千万]+)\\s*(秒(钟)?|分钟|分|小时|时)");
            Matcher mChinese = patternChinese.matcher(duration);
            if (mChinese.find()) {
                String numStr = mChinese.group(1);
                int number = chineseNumberToInt(numStr);
                String unit = mChinese.group(2);
                Log.d(TAG, "解析到中文数字: " + numStr + " => " + number + ", 单位: " + unit);
                if (number <= 0) return 0;
                if (unit.startsWith("秒")) return number * 1000L;
                if (unit.startsWith("分钟") || unit.equals("分")) return number * 60 * 1000L;
                return number * 60 * 60 * 1000L;
            }

            // 如果只是单位名称（如"分钟"或"秒钟"），默认为1个单位
            String durNoSpace = duration.replaceAll("\\s+", "");
            switch (durNoSpace) {
                case "秒":
                case "秒钟":
                    return 1000L;
                case "分钟":
                case "分":
                    return 60 * 1000L;  // 1分钟
                case "小时":
                case "时":
                    return 60 * 60 * 1000L;  // 1小时
                default:
                    Log.e(TAG, "无法解析时长格式: " + duration);
                    return 0;
            }

        } catch (Exception e) {
            Log.e(TAG, "解析时长时发生错误: " + e.getMessage());
            e.printStackTrace();
            return 0;
        }
    }

    // 把中文数字（最多到万级）转换为整数。支持：零一二两三四五六七八九十百千万，例如 "十"=>10、"二十三"=>23、"两"=>2
    private int chineseNumberToInt(String chinese) {
        if (chinese == null || chinese.isEmpty()) return 0;
        int result = 0;
        int section = 0; // 当前节的值
        int number = 0; // 当前数字

        for (int i = 0; i < chinese.length(); i++) {
            char c = chinese.charAt(i);
            int digit = -1;
            switch (c) {
                case '零': digit = 0; break;
                case '一': digit = 1; break;
                case '二': case '两': digit = 2; break;
                case '三': digit = 3; break;
                case '四': digit = 4; break;
                case '五': digit = 5; break;
                case '六': digit = 6; break;
                case '七': digit = 7; break;
                case '八': digit = 8; break;
                case '九': digit = 9; break;
            }

            if (digit >= 0) {
                number = digit;
            } else {
                // 遇到单位
                switch (c) {
                    case '十':
                        if (number == 0) number = 1;
                        section += number * 10;
                        number = 0;
                        break;
                    case '百':
                        if (number == 0) number = 1;
                        section += number * 100;
                        number = 0;
                        break;
                    case '千':
                        if (number == 0) number = 1;
                        section += number * 1000;
                        number = 0;
                        break;
                    case '万':
                        section = (section + number) * 10000;
                        result += section;
                        section = 0;
                        number = 0;
                        break;
                    default:
                        // 非数字、非单位字符，忽略
                        break;
                }
            }
        }

        return result + section + number;
    }

    private void handleMusicRadioCommand(JSONObject commandObj) throws JSONException {
        if (commandObj.has("function")) {
            //play music in voice app
            JSONObject function = commandObj.getJSONObject("function");
            if (function.has("arguments")){
                String arguments = function.getString("arguments");
                Log.d(TAG, "arguments: " + arguments);
                JSONArray args = new JSONArray(arguments);
                for (int i = 0; i < args.length(); i++) {
                    if (args.get(i) instanceof JSONObject) {
                        JSONObject arg = (JSONObject) args.get(i);
                        if (arg.has("music_info")) {
                            String musicInfo = arg.getString("music_info");
                            playMp3Url(musicInfo);
                        }
                    }
                }
            }
        }else if (commandObj.has("params")) {
            //play music in multimodal app
            JSONArray params = commandObj.getJSONArray("params");
            Log.d(TAG, "params: " + params.toString());
            JSONObject param  = (JSONObject) params.get(0);
            if (param.has("normValue")) {
                String musicInfo = param.getString("normValue");
                playMp3Url(musicInfo);
            }

        }
    }

    private void playMp3Url(String jsonString) throws JSONException {
        JSONObject info  = new JSONObject(jsonString);
        if (info.has("audios")) {
            String audioMp3 = info.getString("audios");
            // play mp3 by url，like  "https://dashscope.oss-cn-beijing.aliyuncs.com/samples/audio/FreePD_mp3s/Miscellaneaous_Chill_mp3/Tahistaeva%20Aeg.mp3"
            final String musicUrl = audioMp3;
            final String musicName = info.getString("songName");
            networkMp3Player.play(musicUrl, new NetworkMp3Player.OnPlayCallback(){
                @Override
                public void onPlayStart() {
                    Log.d(TAG, "mp3开始播放");
                    // 更新音乐播放UI
                    runOnUiThread(() -> {
                        musicPlayerContainer.setVisibility(View.VISIBLE);
                        tvMusicInfo.setText("正在播放: "+ musicName);
                        progressMusic.setVisibility(View.VISIBLE);
                    });
                }

                @Override
                public void onPlayComplete() {
                    Log.d(TAG, "mp3播放完成");
                    // 隐藏音乐播放UI
                    runOnUiThread(() -> {
                        musicPlayerContainer.setVisibility(View.GONE);
                        progressMusic.setVisibility(View.GONE);
                    });
                }

                @Override
                public void onPlayError(String error) {
                    isExecutingCommand = false;
                    // 隐藏音乐播放UI
                    runOnUiThread(() -> {
                        musicPlayerContainer.setVisibility(View.GONE);
                        progressMusic.setVisibility(View.GONE);
                        showToast("音乐播放出错: " + error);
                    });
                }
            });
        }
    }

    private void executeVQACommand() {
        showToast("开始拍照");
        isVqaMode = true;

        // 目前移动端拍照需要上传图片到oss，或其他第三方文件服务，生成图片url地址调用图文理解Agent
        // 以下TakePictureAndUploadOSS是基于阿里云OSS的实现，您可以替换为自有或三方服务
        // TakePictureAndUploadOSS 基于阿里云OSS SDK实现，需要开通OSS服务，并在这个类中修改OSS接入
        // 需要的AK，SK，Endpoint，BucketName等信息
        // TakePictureAndUploadOSS takePictureAndUploadOSS = new TakePictureAndUploadOSS(mContext, url -> {
        //      //下方发送请求
        // });
        // takePictureAndUploadOSS.takePicture();

        //除了上传 URL 之外，VQA 也支持通过 base64编码格式直接传图片。这种方式支持尺寸小于 180KB 的图片.type为“base64”,value为图片base64编码
        runOnUiThread(() ->
        {
            //增加请求延时。通过实际拍照实现可以忽略
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            MultiModalRequestParam updateParams = MultiModalRequestParam
                    .builder()
                    .build();
            JSONObject imageObject = new JSONObject();
            try{
                imageObject.put("type", "url");
                imageObject.put("value", authParams.getVqaImageLink());
                List<JSONObject> images = new ArrayList<>();
                images.add(imageObject);

                updateParams.setImages(images);
            }catch (JSONException e){
                e.printStackTrace();
            }
            multiModalDialog.requestToRespond("prompt", "",updateParams.getParametersAsJson());
            isExecutingCommand = false;
        });
    }

    private void startVideoMode() {
        if (isVideoMode) return;

        try {
            MultiModalRequestParam updateParams = MultiModalRequestParam.builder().build();
            JSONObject videoObj = new JSONObject();
            videoObj.put("action", "connect");
            videoObj.put("type", "voicechat_video_channel");

            Log.d(TAG, "初始化视频模式参数: " + videoObj.toString());
            List<JSONObject> videos = new ArrayList<>();
            videos.add(videoObj);

            updateParams.setBizParams(MultiModalRequestParam.BizParams.builder()
                    .videos(videos).build());

            Log.d(TAG, "启动视频模式参数: " + updateParams.getParametersAsJson().toString());
            multiModalDialog.requestToRespond("prompt", "", updateParams.getParametersAsJson());
            multiModalDialog.setVideoContainer(videoContainer, uiHandler);
            
            Log.d(TAG, "启动视频模式");
            //startVideoFrameStreaming();
            

            isVideoMode = true;

            // 启动视频帧流发送（每 500ms 发送一帧）
            startVideoFrameStreaming();

            runOnUiThread(() -> {
                // 显示悬浮窗，并为主内容区域预留空间，避免遮挡任何文字/按钮
                applyVideoModeSafeInsets(true);
                videoContainer.setVisibility(View.VISIBLE);
                videoContainer.removeAllViews();
                enableVideoDrag();
                

                // 初始化texture view
                TextureView textureView = new TextureView(this);
                cameraTextureView = textureView;
                textureView.setOpaque(false);
                // diagnostics: log surface/texture events and view sizes
                textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                    @Override
                    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                        Log.d(TAG, "onSurfaceTextureAvailable: surfaceSize=" + width + "x" + height
                                + ", textureViewSize=" + textureView.getWidth() + "x" + textureView.getHeight()
                                + ", containerSize=" + videoContainer.getWidth() + "x" + videoContainer.getHeight());
                        // 保持与 CameraManager.init(...) 时使用的分辨率一致
                        Size ps = CameraManager.getInstance().getPreviewSize();
                        surface.setDefaultBufferSize(ps.getWidth(), ps.getHeight());

                        // 预览居中裁剪显示（PiP小窗更清晰，不拉伸）
                        textureView.post(() -> applyCenterCropTransform(textureView, ps.getWidth(), ps.getHeight()));

                        CameraManager.getInstance().startPreview(surface);
                    }

                    @Override
                    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                        Log.d(TAG, "onSurfaceTextureSizeChanged: " + width + "x" + height);
                        Size ps = CameraManager.getInstance().getPreviewSize();
                        textureView.post(() -> applyCenterCropTransform(textureView, ps.getWidth(), ps.getHeight()));
                    }

                    @Override
                    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                        Log.d(TAG, "onSurfaceTextureDestroyed");
                        // 停止/释放摄像头预览（CameraManager.destroy 会关闭 session/imageReader）
                        CameraManager.getInstance().destroy();
                        return true; // 允许系统释放 SurfaceTextur
                    }

                    @Override
                    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                        // called when a new frame is available to this SurfaceTexture
                        try {
                            long ts = surface.getTimestamp();
                            Log.v(TAG, "onSurfaceTextureUpdated timestamp=" + ts);
                        } catch (Exception e) {
                            Log.v(TAG, "onSurfaceTextureUpdated");
                        }
                    }
                });
                
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                videoContainer.addView(textureView, lp);
            });
            
        } catch (JSONException e) {
            Log.e(TAG, "启动视频模式失败", e);
        }
    }

    private void applyVideoModeSafeInsets(boolean enabled) {
        // 悬浮窗不再挤压主内容，保持原始 padding
        mainContent.setPadding(
            mainContentPaddingLeft,
            mainContentPaddingTop,
            mainContentPaddingRight,
            mainContentPaddingBottom
        );
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void applyCenterCropTransform(@NonNull TextureView textureView, int contentWidth, int contentHeight) {
        if (contentWidth <= 0 || contentHeight <= 0) return;
        int viewWidth = textureView.getWidth();
        int viewHeight = textureView.getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) return;

        // Center-crop: 放大并以中心为锚点裁剪，保证画面铺满 view（避免出现背景白边）
        float viewRatio = (float) viewWidth / (float) viewHeight;
        float contentRatio = (float) contentWidth / (float) contentHeight;

        float scaleX = 1f;
        float scaleY = 1f;
        if (contentRatio > viewRatio) {
            // 内容更“宽”，放大 X 方向以裁剪左右
            scaleX = contentRatio / viewRatio;
        } else {
            // 内容更“窄”，放大 Y 方向以裁剪上下
            scaleY = viewRatio / contentRatio;
        }

        float pivotX = viewWidth / 2f;
        float pivotY = viewHeight / 2f;

        android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.setScale(scaleX, scaleY, pivotX, pivotY);
        textureView.setTransform(matrix);
    }

    ////////// Live2D lip-sync helpers //////////
    private void triggerLive2DTalkStart() {
        evalLive2DJs("window.startTalkMotion && window.startTalkMotion();");
    }

    private void triggerLive2DTalkStop() {
        evalLive2DJs("window.stopTalkMotion && window.stopTalkMotion(); window.setMouthOpen && window.setMouthOpen(0);");
    }

    private void updateLive2DMouth(int level) {
        // Map integer level to 0..1; adjust divisor if needed based on actual levels
        float normalized = Math.max(0f, Math.min(1f, level / 100f));
        String js = String.format(Locale.US,
                "window.setMouthOpen && window.setMouthOpen(%.3f);",
                normalized);
        evalLive2DJs(js);
    }

    private void evalLive2DJs(@NonNull String js) {
        if (live2dWebView == null) return;
        runOnUiThread(() -> live2dWebView.evaluateJavascript(js, null));
    }

    @SuppressLint("ClickableViewAccessibility")
    private void enableVideoDrag() {
        if (videoContainer == null) return;
        videoContainer.setClickable(true); // keep accessibility happy when handling touch
        videoContainer.setOnTouchListener(new View.OnTouchListener() {
            float startX;
            float startY;
            int startLeft;
            int startTop;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                View parent = (View) v.getParent();
                if (parent == null) return false;
                RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) v.getLayoutParams();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getRawX();
                        startY = event.getRawY();
                        startLeft = lp.leftMargin;
                        startTop = lp.topMargin;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - startX;
                        float dy = event.getRawY() - startY;
                        int newLeft = startLeft + Math.round(dx);
                        int newTop = startTop + Math.round(dy);
                        int maxLeft = parent.getWidth() - v.getWidth();
                        int maxTop = parent.getHeight() - v.getHeight();
                        lp.leftMargin = clamp(newLeft, 0, Math.max(maxLeft, 0));
                        lp.topMargin = clamp(newTop, 0, Math.max(maxTop, 0));
                        lp.rightMargin = 0;
                        lp.bottomMargin = 0;
                        v.setLayoutParams(lp);
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.performClick(); // accessibility: announce click action even though we drag
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void stopVideoMode() {
        isVideoMode = false;
        runOnUiThread(() -> {
            applyVideoModeSafeInsets(false);
            videoContainer.setVisibility(View.GONE);
            videoContainer.removeAllViews();
        });

        cameraTextureView = null;

        // 停止视频帧流发送线程
        stopVideoFrameStreaming();

        //multiModalDialog.stop();
        isExecutingCommand = false;
        runOnUiThread(()->updateStateUI(currentState));
        // 向服务器发送命令执行完成的反馈
        multiModalDialog.requestToRespond("transcript", "已退出视频通话", null);
    }

    private void executeDefaultCommand() {
        // 等待进入监听状态或空闲状态
        while (currentState != DialogState.DIALOG_LISTENING || currentState != DialogState.DIALOG_IDLE) {
            //push2talk 模式下一轮会先流转到 idle 状态。
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        multiModalDialog.requestToRespond("transcript", "执行成功", null);
        isExecutingCommand = false;
    }

    /////////////////////////////////////// 工具方法 ///////////////////////////////////////

    private Pair<String, Boolean> parseTextDetail(String response) {
        try {
            JSONObject payload = new JSONObject(response).getJSONObject("payload");
            if (payload.has("output")) {
                JSONObject output = payload.getJSONObject("output");
                String text = output.getString("text");
                boolean finished = output.getBoolean("finished");
                return new Pair<>(text, finished);
            }
        } catch (Exception e) {
            Log.e(TAG, "解析文本详情失败", e);
        }
        return null;
    }

    private String parseResponseCommand(String response) {
        try {
            JSONObject payload = new JSONObject(response).getJSONObject("payload");
            if (payload.has("output")) {
                JSONObject output = payload.getJSONObject("output");
                if (output.has("extra_info") && !output.isNull("extra_info")) {
                    JSONObject extraInfo = new JSONObject(output.getString("extra_info"));
                    if (extraInfo.has("commands")) {
                        String commands = extraInfo.getString("commands");
                        if (commands.length() > 6) { // 过滤空命令
                            return commands;
                        }
                    }else if (extraInfo.has("tool_calls")) {
                        String tool_calls = extraInfo.getString("tool_calls");
                        if (tool_calls.length() > 6) { // 过滤空命令
                            return tool_calls;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "解析命令失败", e);
        }
        return null;
    }

    private MultiModalRequestParam buildRequestParams() {

        MultiModalRequestParam.UpStream.ReplaceWord replaceWord = new MultiModalRequestParam.UpStream.ReplaceWord();
        replaceWord.setTarget("一加一");
        replaceWord.setSource("1加1");
        replaceWord.setMatchMode("partial");

        return MultiModalRequestParam.builder()
                .clientInfo(MultiModalRequestParam.ClientInfo.builder()
                        .device(MultiModalRequestParam.ClientInfo.Device.builder()
                                .uuid("uuid_12345").build()) // 请配置为您的设备UUID
                        .userId("your_user_id")  //userid 需要每个用户唯一，建议使用设备UUID。 对话历史会使用 userId关联
                        .build())
                .upStream(MultiModalRequestParam.UpStream.builder()
                        .asrPostProcessing(Collections.singletonList(replaceWord))
                        .mode("duplex")
                        .type("AudioAndVideo")
                        .build())
                .downStream(MultiModalRequestParam.DownStream.builder()
                        .voice("longanhuan") //tts 音色对应的模型需要和管控台配置的模型一致。longxiaochun_v2对应了cosyvoice_v2
                        .sampleRate(48000)
                        .intermediateText("transcript")
                        .build())
                .build();
    }

    /////////////////////////////////////// 音频相关 ///////////////////////////////////////

    private final IRecorderCallback audioRecorderCallback = new IRecorderCallback() {
        @Override
        public void onRecorderStart() {
            Log.d(TAG, "录音开始");
        }

        @Override
        public void onRecorderStop() {
            Log.d(TAG, "录音停止");
        }

        @Override
        public int onRecorderData(byte[] data, int len, boolean firstPack) {
            if (multiModalDialog != null && authParams.getChainMode() != Constant.ChainMode.RTC  ) {
                multiModalDialog.sendAudioData(data);
            }else if (authParams.getChainMode() == Constant.ChainMode.RTC && !isRtcUseInternalAudio()) {
                multiModalDialog.sendAudioData(data);
            }
            return 0;
        }

        @Override
        public void onRecordStateChanged(TYAudioRecorder.RecordState state) {
            Log.d(TAG, "录音状态变化: " + state);
        }
    };

    private AudioPlayerCallback getAudioPlayerCallback() {
        return new AudioPlayerCallback() {
            @Override
            public void playStart() {
                Log.d(TAG, "播放开始");
                multiModalDialog.sendResponseStarted();
                triggerLive2DTalkStart();
            }

            @Override
            public void playOver(boolean interrupt, int delay_ms) {
                Log.d(TAG, "播放结束");
                if (multiModalDialog != null) {
                    multiModalDialog.sendResponseEnded();
                }
                triggerLive2DTalkStop();
            }

            @Override
            public void playSoundLevel(int level) {
                // 实时映射到 Live2D 嘴型
                updateLive2DMouth(level);
            }

            @Override
            public void showLog(String text, String tag) {
                // 显示日志
            }

            @Override
            public int onPlayerData(byte[] data, int len) {
                if (multiModalDialog != null && multiModalDialog.isDuplexMode()) {
                    multiModalDialog.sendRefData(data);
                }
                return 0;
            }

            @Override
            public void playerStateChanged(int action) {
                // 播放状态变化
            }
        };
    }

    /////////////////////////////////////// 生命周期 ///////////////////////////////////////

    @Override
    protected void onStop() {
        super.onStop();
        if (isFinishing()) {
            ThreadPoolUtil.runOnSubThread(() -> {
                audioRecorder.pause();
                audioPlayer.stop(true, false);
            });
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Activity销毁");

        if (multiModalDialog != null) {
            multiModalDialog.destroy();
        }
        if (audioPlayer != null) {
            audioPlayer.stop(true, false);
            audioPlayer.releaseAudioTrack(true);
        }

        if (audioRecorder != null) {
            audioRecorder.pause();
        }

        if (networkMp3Player != null) {
            networkMp3Player.release();
        }

        if (live2dWebView != null) {
            try {
                live2dWebView.loadUrl("about:blank");
                live2dWebView.onPause();
                live2dWebView.destroy();
            } catch (Exception ignored) {
            }
            live2dWebView = null;
        }

        CameraManager.getInstance().destroy();

        // 停止视频帧流发送线程（防止后台泄露）
        stopVideoFrameStreaming();

        if (uiHandler != null) {
            uiHandler.removeCallbacksAndMessages(null);
        }



    }

    /**
     * DEMO 流程未调用。演示在 websocket 链路中实现 liveAI
     * 启动视频帧流，每 500ms 发送一次图片帧
     */
    private void startVideoFrameStreaming() {
        // 如果已经在运行，则不重复启动
        if (videoStreamingThread != null && videoStreamingThread.isAlive()) return;

        videoStreamingRunning = true;
        videoStreamingThread = new Thread(() -> {
            try {
                while (videoStreamingRunning && !Thread.currentThread().isInterrupted()) {
                    Log.d(TAG, "发送视频帧图片");
                    Thread.sleep(500);

                    JSONObject extraObject = new JSONObject();
                    extraObject.put("images", getMockOSSImage());

                    // 上传
                    multiModalDialog.updateInfo(extraObject);
                }
            } catch (InterruptedException e) {
                Log.d(TAG, "视频帧流发送线程被中断", e);
                Thread.currentThread().interrupt();
            } catch (JSONException e) {
                Log.d(TAG, "视频帧图片发送失败", e);
            } finally {
                videoStreamingRunning = false;
            }
        });

        videoStreamingThread.setDaemon(true);
        videoStreamingThread.setName("LiveAI-VideoStreaming");
        videoStreamingThread.start();
    }

    private void stopVideoFrameStreaming() {
        if (videoStreamingThread == null) return;
        // 请求线程停止并中断
        videoStreamingRunning = false;
        videoStreamingThread.interrupt();
        try {
            videoStreamingThread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        videoStreamingThread = null;
    }

    /**
     * build images list request
     * */
    private JSONArray getMockOSSImage() {
         JSONObject imageObject = new JSONObject();
         JSONArray images = new JSONArray();
         try{
             String base64 = getLocalImageBase64();
             if (base64 != null && !base64.isEmpty()) {
                 imageObject.put("type", "base64");
                 imageObject.put("value", base64);
                 images.put(imageObject);
             }
         }catch (Exception e){
             e.printStackTrace();
         }
         return images;
     }

    // read local view (videoContainer) to Base64 JPEG
    private String getLocalImageBase64(){
        try {
            final Bitmap[] bmpHolder = new Bitmap[1];
            final CountDownLatch latch = new CountDownLatch(1);

            runOnUiThread(() -> {
                try {
                    // Prefer grabbing the actual camera preview frame.
                    if (cameraTextureView != null
                            && cameraTextureView.isAvailable()
                            && cameraTextureView.getWidth() > 0
                            && cameraTextureView.getHeight() > 0) {
                        bmpHolder[0] = cameraTextureView.getBitmap();
                        return;
                    }

                    // Fallback: draw the container (may not include TextureView content).
                    if (videoContainer == null) return;
                    int w = videoContainer.getWidth();
                    int h = videoContainer.getHeight();
                    if (w <= 0 || h <= 0) return;
                    Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bmp);
                    videoContainer.draw(canvas);
                    bmpHolder[0] = bmp;
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });

            // wait up to 1s for UI capture
            latch.await(1, TimeUnit.SECONDS);

            Bitmap bmp = bmpHolder[0];
            if (bmp == null) return "";

            // Keep payload small (VQA/base64 often has size limits). Scale down if needed.
            final int maxDim = 480;
            int bw = bmp.getWidth();
            int bh = bmp.getHeight();
            if (bw > 0 && bh > 0) {
                int larger = Math.max(bw, bh);
                if (larger > maxDim) {
                    float scale = (float) maxDim / (float) larger;
                    int tw = Math.max(1, Math.round(bw * scale));
                    int th = Math.max(1, Math.round(bh * scale));
                    Bitmap scaled = Bitmap.createScaledBitmap(bmp, tw, th, true);
                    if (scaled != bmp) {
                        bmp.recycle();
                        bmp = scaled;
                    }
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int quality = 60;
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos);
            byte[] bytes = baos.toByteArray();
            // If still large, reduce quality a bit.
            while (bytes.length > 180 * 1024 && quality > 30) {
                quality -= 10;
                baos.reset();
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos);
                bytes = baos.toByteArray();
            }
            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    /////////////////////////////////////// 工具方法 ///////////////////////////////////////

    private void showToast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    private void updateMusicPlayerUI(boolean isPlaying) {
        runOnUiThread(() -> {
            if (isPlaying) {
                musicPlayerContainer.setVisibility(View.VISIBLE);
                progressMusic.setVisibility(View.VISIBLE);
            } else {
                musicPlayerContainer.setVisibility(View.GONE);
                progressMusic.setVisibility(View.GONE);
            }
        });
    }


}

