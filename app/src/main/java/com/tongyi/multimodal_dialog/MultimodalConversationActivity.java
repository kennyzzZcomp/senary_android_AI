package com.tongyi.multimodal_dialog;

import static com.tongyi.multimodal_dialog.MultiModalDialog.isRtcUseInternalAudio;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

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
    private FrameLayout videoContainer;
    private ScrollView scrollLogs;
    private Handler uiHandler;

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
    private boolean isVideoMode = false;
    private boolean isExecutingCommand = false;
    private String dialogId;
    private String taskId;

    // 性能监控
    private long initStartTime;
    private long initEndTime;
    private long connectCost = 0;

    private boolean enableKeywordSpotting = false;
    private boolean lastAsrFinished = false; // 添加这个标志位来跟踪上一次ASR是否已完成

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
        uiHandler = new Handler();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initializeUI() {
        // 绑定UI组件
        tvLogs = findViewById(R.id.tv_logs);
        tvState = findViewById(R.id.tv_state);
        btnInterrupt = findViewById(R.id.btn_interrupt);
        btnExit = findViewById(R.id.btn_exit);
        tvInterruptText = findViewById(R.id.tv_interrupt_text);
        videoContainer = findViewById(R.id.video_container);
        scrollLogs = findViewById(R.id.scroll_logs);
        musicPlayerContainer = findViewById(R.id.music_player_container);
        tvMusicInfo = findViewById(R.id.tv_music_info);
        btnStopMusic = findViewById(R.id.btn_stop_music);
        progressMusic = findViewById(R.id.progress_music);

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

        // 启用唤醒，默认唤醒词为"小云小云"
//        if (enableKeywordSpotting){
//            //如果开启唤醒，那么需要先启动录音
//            MultiModalDialog.wsUseInternalVAD = true;
//            multiModalDialog.enableKWS(true, false);
//        }
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
            runOnUiThread(() -> updateStateUI(state));
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

        if (multiModalDialog.isPush2TalkMode()) {
            runOnUiThread(() -> btnInterrupt.setVisibility(View.VISIBLE));
        }

        // 启动视频模式
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
                // AI回复直接显示
                appendLogMessage(messageFrom + ": " + text);
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

            tvLogs.setText(newText);
            scrollToBottom();
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

    private void appendLogMessage(String message) {
        runOnUiThread(() -> {
            String currentText = tvLogs.getText().toString();
            String newText = currentText.equals("等待对话开始...") ? message :
                    currentText + "\n" + message;
            tvLogs.setText(newText);
            scrollToBottom();
        });
    }

    private void scrollToBottom() {
        scrollLogs.post(() -> scrollLogs.fullScroll(View.FOCUS_DOWN));
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
                    case "play_music":
                        handleMusicRadioCommand(commandObj);
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
                    default:
                        break;
                }
            }



        } catch (Exception e) {
            Log.e(TAG, "命令执行失败", e);
            isExecutingCommand = false;
        }
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

            List<JSONObject> videos = new ArrayList<>();
            videos.add(videoObj);

            updateParams.setBizParams(MultiModalRequestParam.BizParams.builder()
                    .videos(videos).build());

            multiModalDialog.requestToRespond("prompt", "", updateParams.getParametersAsJson());
            multiModalDialog.setVideoContainer(videoContainer, uiHandler);

            isVideoMode = true;
            runOnUiThread(() -> videoContainer.setVisibility(View.VISIBLE));

        } catch (JSONException e) {
            Log.e(TAG, "启动视频模式失败", e);
        }
    }

    private void stopVideoMode() {
        isVideoMode = false;
        runOnUiThread(() -> videoContainer.setVisibility(View.GONE));

        multiModalDialog.stop();
        isExecutingCommand = false;
    }

    private void executeDefaultCommand() {
        // 等待进入监听状态
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
            }

            @Override
            public void playOver(boolean interrupt, int delay_ms) {
                Log.d(TAG, "播放结束");
                if (multiModalDialog != null) {
                    multiModalDialog.sendResponseEnded();
                }
            }

            @Override
            public void playSoundLevel(int level) {
                // 音量级别
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

        CameraManager.getInstance().destroy();

        if (uiHandler != null) {
            uiHandler.removeCallbacksAndMessages(null);
        }
    }

    /**
     * DEMO 流程未调用。演示在 websocket 链路中实现 liveAI
     * 启动视频帧流，每 500ms 发送一次图片帧
     */
    private void startVideoFrameStreaming() {
        Thread videoStreamingThread = new Thread(() -> {
            try {
                while ( !Thread.currentThread().isInterrupted()) {
                    Thread.sleep(500);

                    JSONObject extraObject = new JSONObject();
                    extraObject.put("images", getMockOSSImage());

                    multiModalDialog.updateInfo(extraObject);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        });

        videoStreamingThread.setDaemon(true);
        videoStreamingThread.setName("LiveAI-VideoStreaming");
        videoStreamingThread.start();

    }

    /**
     * build images list request
     * */
    private static JSONArray getMockOSSImage() {
        JSONObject imageObject = new JSONObject();
        JSONArray images = new JSONArray();
        try{

            imageObject.put("type", "base64");
            imageObject.put("value", getLocalImageBase64());

            images.put(imageObject);

        }catch (Exception e){
            e.printStackTrace();
        }
        return images;
    }

    //read local image to base64
    private static String getLocalImageBase64(){
        return "";
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