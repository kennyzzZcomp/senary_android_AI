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
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;
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
    private static String workspaceId = "llm-2d2jbauuwkp1250n";
    private static String apiKey = "sk-cebc306c1a7d44579af8d99c199789a2";
    private static String appId = "ddc2509a3d01433f876f50c1fc4c0865";
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
    private boolean isWs = true; //默认websocket
    private boolean isAudio = true;


    private String[] permissions = new String[]{
            permission.RECORD_AUDIO,
            // permission.CAMERA, //如需视频能力，打开此权限. 测试机没有相机
            permission.WRITE_EXTERNAL_STORAGE,
            permission.ACCESS_COARSE_LOCATION
    };
    @SuppressLint({"MissingInflatedId", "SetTextI18n"})
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DeviceUtil.setStatusBarColor(this);
        setContentView(R.layout.activity_entrance);

        // Drawer / Toolbar 初始化（必须在 setContentView 之后）
        DrawerLayout drawerLayout = findViewById(R.id.drawer_layout);
        NavigationView navView = findViewById(R.id.nav_view);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this,
                drawerLayout,
                toolbar,
                R.string.app_name,
                R.string.app_name
        );
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        // 三个“房间”的引用：主页 / 配置页 / Fragment页
        View layoutHome = findViewById(R.id.layout_home);
        View layoutConfig = findViewById(R.id.layout_config);
        View fragmentContainer = findViewById(R.id.layout_fragment_container);

        Runnable showHome = () -> {
            fragmentContainer.setVisibility(View.GONE);
            layoutHome.setVisibility(View.VISIBLE);
            layoutConfig.setVisibility(View.GONE);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setTitle("AI 实验室");
            }
        };

        Runnable showConfig = () -> {
            fragmentContainer.setVisibility(View.GONE);
            layoutHome.setVisibility(View.GONE);
            layoutConfig.setVisibility(View.VISIBLE);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setTitle("参数配置");
            }
        };
        // 切换到fragment的显示, 显示关于页面
        Runnable showAbout = () -> {
            layoutHome.setVisibility(View.GONE);
            layoutConfig.setVisibility(View.GONE);
            fragmentContainer.setVisibility(View.VISIBLE);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setTitle("关于");
            }
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.layout_fragment_container, new AboutFragment())
                    .commit();
        };

        // 默认显示主页
        showHome.run();

        // 侧边栏点击事件：切换显示
        navView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home) {
                showHome.run();
                item.setChecked(true);
            } else if (id == R.id.nav_config) {
                showConfig.run();
                item.setChecked(true);
            } else if (id == R.id.nav_about) {
                showAbout.run();
                item.setChecked(true);
            } else {
                Toast.makeText(this, item.getTitle(), Toast.LENGTH_SHORT).show();
            }

            drawerLayout.closeDrawers();
            return true;
        });

        // 主页按钮：直接去配置页
        // findViewById(R.id.btn_go_to_config).setOnClickListener(v -> showConfig.run());

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

//        if (ContextCompat.checkSelfPermission(this, permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
//            requestPermissions(new String[]{permission.CAMERA}, PERMISSION_REQUEST_CODE);
//        }

    }

    // 方便把权限名转成更友好的中文提示
    private String prettyPermissionName(String perm) {
        if (permission.RECORD_AUDIO.equals(perm)) return "麦克风";
        // if (permission.CAMERA.equals(perm)) return "相机";
        if (permission.ACCESS_COARSE_LOCATION.equals(perm)) return "位置";
        if (permission.WRITE_EXTERNAL_STORAGE.equals(perm)) return "存储";
        return perm;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;

            StringBuilder missing = new StringBuilder();
            StringBuilder missingForLog = new StringBuilder();
            boolean hasNeverAskAgain = false;

            // 逐项检查本次回调返回的权限结果
            for (int i = 0; i < permissions.length; i++) {
                String perm = permissions[i];
                int result = (i < grantResults.length) ? grantResults[i] : PackageManager.PERMISSION_DENIED;

                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;

                    // UI 提示用
                    if (missing.length() > 0) missing.append("、");
                    missing.append(prettyPermissionName(perm));

                    // Log 用
                    missingForLog.append(perm).append("(").append(prettyPermissionName(perm)).append(") ");

                    // 用户是否勾选了“不再询问”
                    // 返回 false 代表：不再弹框了（可能是勾选了不再询问 / 或策略不允许）
                    if (!ActivityCompat.shouldShowRequestPermissionRationale(this, perm)) {
                        hasNeverAskAgain = true;
                    }
                }
            }

            if (allGranted) {
                launchVideoChat();
            } else {
                Log.w(TAG, "Permission denied. Missing: " + missingForLog);
                String toast = "权限申请失败，缺少：" + (missing.length() == 0 ? "(未知)" : missing);
                if (hasNeverAskAgain) {
                    toast += "（已勾选不再询问，请到系统设置里手动开启）";
                }
                Toast.makeText(this, toast, Toast.LENGTH_LONG).show();
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
