package com.tongyi.multimodal_dialog;

import android.Manifest.permission;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.tongyi.multimodal_conversation.R;
import com.tongyi.multimodal_dialog.data.MultimodalParams;
import com.tongyi.multimodal_dialog.data.request.MultiModalRequestParam;
import com.tongyi.multimodal_dialog.util.ConvLog;
import com.tongyi.multimodal_dialog.util.DeviceUtil;
import com.tongyi.multimodal_dialog.video.TYVideoConfig;

/**
 * @author songsong.shao
 * @date 2024/10/15
 * description:
 */
public class EntranceActivity extends AppCompatActivity {
    private static final String TAG = EntranceActivity.class.getSimpleName();
    private static final int PERMISSION_REQUEST_CODE = 100;
    //线上rtc
    private static String url = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";
    private static String url_rtc = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    private static String chain = "websocket";
    private static String chain_rtc = "rtc";
    private static String workspaceId = "YOUR_WORKSPACE_ID";
    private static String apiKey = "YOUR_API_KEY";
    private static String appId = "YOUR_APP_ID";
    private static String vqaImgLink = "https://help-static-aliyun-doc.aliyuncs.com/assets/img/zh-CN/7043267371/p909896.png";


    private EditText apiKeyView;
    private EditText chainView;
    private EditText workspaceIdView;
    private EditText urlView;
    private Spinner convTypeSpinner;
    private CheckBox cbChainSelect;//选择链路
    private EditText vqaLinkView;
    private EditText modelView;

    private EditText appidView;
//    private CheckBox cbType ;
    private boolean isWs = true;
    private boolean isAudio = true;


    private String[] permissions = new String[]{
            permission.RECORD_AUDIO,
            permission.CAMERA,
            permission.WRITE_EXTERNAL_STORAGE,
            permission.ACCESS_COARSE_LOCATION
    };
    @SuppressLint({"MissingInflatedId", "SetTextI18n"})
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DeviceUtil.setStatusBarColor(this);
        setContentView(R.layout.activity_entrance);

        apiKeyView = (EditText) findViewById(R.id.editView2);
        vqaLinkView = (EditText) findViewById(R.id.editView3);
        chainView = (EditText) findViewById(R.id.editView5);
        workspaceIdView = (EditText) findViewById(R.id.editView6);
        urlView = (EditText) findViewById(R.id.editView9);
        appidView = (EditText) findViewById(R.id.editView11);
        modelView = (EditText) findViewById(R.id.editViewModel);
        convTypeSpinner = (Spinner) findViewById(R.id.spinner);
        cbChainSelect = (CheckBox) findViewById(R.id.checkType);
//        cbType = (CheckBox) findViewById(R.id.checkPri);
        //默认关闭全双工交互
        ConvLog.setLogLevel(ConvLog.DEBUG);

        //初始化参数
        if (!apiKey.isEmpty()){
            apiKeyView.setText(apiKey);
        }
        vqaLinkView.setText(vqaImgLink);
        chainView.setText(chain);
        if (!appId.isEmpty()){
            appidView.setText(appId);
        }
        if (!workspaceId.isEmpty()){
            workspaceIdView.setText(workspaceId);
        }
        urlView.setText(url);
        modelView.setText("multimodal-dialog");
        cbChainSelect.setChecked(true);
        cbChainSelect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(cbChainSelect.isChecked()){
                    isWs = true;
                    Log.i(TAG, "websocket");
                    getInitParam();
                }else{
                    isWs = false;
                    Log.i(TAG, "rtc");
                    getInitParam();
                }
            }
        });



        // 进入页面后，尝试主动申请权限，简化流程
        checkPermission();


        findViewById(R.id.btn_chat).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                genLaunchParams();
                if (!hasPermissions(permissions)) {
                    ActivityCompat.requestPermissions(EntranceActivity.this, permissions, PERMISSION_REQUEST_CODE);
                }else{
                    launchVideoChat();
                }
            }
        });
    }

    private MultimodalParams multimodalParams;

    /**
     * 拉起VideoChat页
     */
    private void launchVideoChat() {
        if (null == multimodalParams) {
            Log.e(TAG, "launch failed without authParams or requestConfig");
            return;
        }
        String modeNow = convTypeSpinner.getSelectedItem().toString();
        Log.i(TAG, "convTypeSpinner switch " + modeNow);

        if (modeNow.equals("tap2talk")) {
            multimodalParams.setDialogMode(Constant.DialogMode.TAP2TALK);
        } else if (modeNow.equals("duplex")) {
            multimodalParams.setDialogMode(Constant.DialogMode.DUPLEX);
        }else {
            multimodalParams.setDialogMode(Constant.DialogMode.PUSH2TALK);
        }


        MultimodalConversationActivity.launch(EntranceActivity.this, multimodalParams);
        Log.d(TAG, "startRTC called payload:" + multimodalParams);
    }

    private void checkPermission() {
        if (ContextCompat.checkSelfPermission(this, permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
            return;
        }
        if (ContextCompat.checkSelfPermission(this, permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{permission.RECORD_AUDIO}, PERMISSION_REQUEST_CODE);
            return;
        }

        //通过网络获取地址
        if (ContextCompat.checkSelfPermission(this, permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST_CODE);
            return;
        }

        if (ContextCompat.checkSelfPermission(this, permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{permission.CAMERA}, PERMISSION_REQUEST_CODE);
        }

    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
//        if (requestCode == PERMISSION_REQUEST_CODE && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
//            launchVideoChat();
//        }

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                launchVideoChat();
            } else {
                Toast.makeText(this, "权限申请失败", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * 生成请求参数
     */
    private void genLaunchParams() {
        try {
            multimodalParams = new MultimodalParams();
            //目前只支持语音模式

            TYVideoConfig videoConfig = new TYVideoConfig(1280,720,2);
            multimodalParams.setVideoConfig(videoConfig);
            //修改交互单双工模式 duplex/tap2talk


            //其他参数
            multimodalParams.setUrl(urlView.getText().toString());
            multimodalParams.setAppid(appidView.getText().toString());
            multimodalParams.setApiKey(apiKeyView.getText().toString());
            multimodalParams.setWorkspaceId(workspaceIdView.getText().toString());
            multimodalParams.setVqaImageLink(vqaLinkView.getText().toString());
            multimodalParams.setDialogMode(Constant.DialogMode.DUPLEX);
            multimodalParams.setModelId(modelView.getText().toString());

            if (isWs) {
                multimodalParams.setChainMode(Constant.ChainMode.WEBSOCKET);
            }else {
                multimodalParams.setChainMode(Constant.ChainMode.RTC);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            multimodalParams = null;
        }
    }

    private void getInitParam(){
        if (isWs) {
            chainView.setText(chain);
            urlView.setText(url);

        }else {
            chainView.setText(chain_rtc);
            urlView.setText(url_rtc);
        }


    }

    private boolean hasPermissions(String[] permissions) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
}
