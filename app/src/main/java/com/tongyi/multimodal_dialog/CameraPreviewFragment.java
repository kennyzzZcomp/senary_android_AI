package com.tongyi.multimodal_dialog;

import android.Manifest;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.core.content.ContextCompat;

import com.tongyi.multimodal_conversation.R;
import com.tongyi.multimodal_dialog.utils.CameraManager;

public class CameraPreviewFragment extends Fragment {
    private static final String TAG = "CameraPreviewFragment";
    private static final int REQ_CAMERA = 200;

    private FrameLayout previewContainer;
    private Button startButton;
    private TextureView textureView;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_camera_preview, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        previewContainer = view.findViewById(R.id.preview_container);
        startButton = view.findViewById(R.id.btn_start_preview);

        startButton.setOnClickListener(v -> startCameraPreview());
    }

    private void startCameraPreview() {
        if (getContext() == null) return;
        Log.d(TAG, "startCameraPreview invoked");
        // 权限检查
        int perm = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA);
        if (perm != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "CAMERA permission not granted, requesting...");
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }

        try {
            // 初始化相机（分辨率可根据需要调整；CameraManager 内部会选择支持的预览尺寸）
            // CameraManager.getInstance().enablePreviewImageReader(true);
            CameraManager.getInstance().init(getContext(), 640, 480); // 初始化相机1280x720
        } catch (Exception e) {
            Log.e(TAG, "Camera init failed", e);
            return;
        }

        // 避免重复创建
        if (textureView != null && textureView.getParent() != null) {
            return;
        }

        textureView = new TextureView(requireContext());
        textureView.setOpaque(true);
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                Log.d(TAG, "Surface available: " + width + "x" + height);
                try {
                    Size ps = CameraManager.getInstance().getPreviewSize();
                    Log.d(TAG, "Setting default buffer size to preview size: " + ps.getWidth() + "x" + ps.getHeight());
                    surface.setDefaultBufferSize(ps.getWidth(), ps.getHeight());
                } catch (Exception e) {
                    Log.w(TAG, "Preview size not available; using default", e);
                }
                CameraManager.getInstance().startPreview(surface);
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                Log.d(TAG, "Surface size changed: " + width + "x" + height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                Log.d(TAG, "Surface destroyed");
                CameraManager.getInstance().destroy();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
                // 有新帧到达
                Log.v(TAG, "onSurfaceTextureUpdated ts=" + surface.getTimestamp());
            }
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        previewContainer.removeAllViews();
        previewContainer.addView(textureView, lp);
        previewContainer.bringToFront();
        previewContainer.setVisibility(View.VISIBLE);
        Log.d(TAG, "TextureView added to container. isAvailable=" + textureView.isAvailable());
        // 如果 TextureView 已经可用，立即使用现有 SurfaceTexture 启动预览
        if (textureView.isAvailable()) {
            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st != null) {
                try {
                    Size ps = CameraManager.getInstance().getPreviewSize();
                    st.setDefaultBufferSize(ps.getWidth(), ps.getHeight());
                } catch (Exception e) {
                    Log.w(TAG, "Preview size not available in immediate path", e);
                }
                Log.d(TAG, "TextureView already available, starting preview immediately");
                CameraManager.getInstance().startPreview(st);
            } else {
                Log.w(TAG, "SurfaceTexture null despite isAvailable=true");
            }
        } else {
            // 等待布局完成后再次检查
            textureView.post(() -> {
                Log.d(TAG, "post-check: isAvailable=" + textureView.isAvailable());
                if (textureView.isAvailable()) {
                    SurfaceTexture st = textureView.getSurfaceTexture();
                    if (st != null) {
                        try {
                            Size ps = CameraManager.getInstance().getPreviewSize();
                            st.setDefaultBufferSize(ps.getWidth(), ps.getHeight());
                        } catch (Exception e) {
                            Log.w(TAG, "Preview size not available in post path", e);
                        }
                        Log.d(TAG, "post path starting preview");
                        CameraManager.getInstance().startPreview(st);
                    }
                } else {
                    Log.w(TAG, "TextureView not available yet; waiting for listener callback");
                }
            });
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            boolean granted = grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (granted) {
                Log.d(TAG, "CAMERA permission granted, starting preview");
                startCameraPreview();
            } else {
                Log.w(TAG, "CAMERA permission denied; cannot start preview");
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        try {
            CameraManager.getInstance().destroy();
        } catch (Exception ignored) {
        }
        if (previewContainer != null) {
            previewContainer.removeAllViews();
        }
        textureView = null;
    }
}
