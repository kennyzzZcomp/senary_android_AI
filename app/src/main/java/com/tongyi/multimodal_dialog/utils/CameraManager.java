package com.tongyi.multimodal_dialog.utils;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import android.graphics.SurfaceTexture;

import java.util.List;
import java.util.ArrayList;

/**
 * @program:aiforce
 * @description: 不开启预览实现拍照
 * @author: yuyong
 * @date: 2024-08-20 14:50
 **/
public class CameraManager {
    private static final String TAG = CameraManager.class.getName();
    private Context context;
    private android.hardware.camera2.CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private static CameraManager mInstance = null;

    private String path = null;
    private HandlerThread backgroundThread;
    private String cameraId;
    private TakePhotoCallBack mCallBack;
    private int deviceRotationDegrees = 0;//设备旋转角度
    private long startTime;
    private int mWidth = 0, mHeight = 0;
    private CameraCharacteristics mCameraCharacteristics = null;
//    private int mImageFormat;
    private OperationType mOperationType;
    private boolean previewing = false;
    private Surface mPreviewSurface;

    private CaptureRequest.Builder mPreviewRequestBuilder;
    private ImageReader mPictureImageReader;
    private ImageReader mPreviewImageReader;
    private CameraCaptureSession mCaptureSession;
    // picture and preview sizes
    private Size mPictureSize;
    private Size mPreviewSize;
    // background handler alias used by preview-related APIs
    private Handler mBackgroundHandler;
    // optional preview buffer callbacks (kept empty unless preview is used)
    private final List<Object> mPreviewBufferCallbacks = new ArrayList<>();
    // UI thread handler and preview dimensions used by startPreview
    private Handler mUIHandler;
    private int mPreviewWidth = 0;
    private int mPreviewHeight = 0;
    // pending preview surface when startPreview called before camera is opened
    private SurfaceTexture mPendingPreviewSurfaceTexture;
    // enable optional preview ImageReader for frame debugging/consumption
    private boolean mEnablePreviewImageReader = false;

    public static CameraManager getInstance() {
        if (mInstance == null) {
            synchronized (CameraManager.class) {
                if (mInstance == null) {
                    mInstance = new CameraManager();
                }
            }
        }
        return mInstance;
    }

    public void init(Context context,int width,int height) {
        this.context = context;
        cameraManager = (android.hardware.camera2.CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        cameraId = getFacingCameraId(cameraManager);
        this.mWidth = width;
        this.mHeight = height;
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        mBackgroundHandler = new Handler(backgroundThread.getLooper());
        // UI handler for callbacks to main thread
        mUIHandler = new Handler(Looper.getMainLooper());
        //初始化时计算设备旋转角度
        try {
            mCameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
            deviceRotationDegrees = getDeviceRotationDegrees(context, mCameraCharacteristics);
            if (!isSupportOutputSize(width, height)) {
                Log.e(TAG,"The resolution is not supported");
                return;
            }
            // initialize still-picture size (JPEG)
            mPictureSize = new Size(width, height);
            // select a supported preview size for SurfaceTexture
            StreamConfigurationMap map = mCameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                Size[] previewSizes = map.getOutputSizes(SurfaceTexture.class);
                mPreviewSize = choosePreviewSize(previewSizes, width, height);
                Log.d(TAG, "Selected preview size: " + (mPreviewSize != null ? (mPreviewSize.getWidth() + "x" + mPreviewSize.getHeight()) : "null"));
            } else {
                mPreviewSize = new Size(width, height);
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "get deviceRotationDegrees error:" + e.getMessage());
            e.printStackTrace();
        }
        ((Activity) context).runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        startTime = System.currentTimeMillis();
                        // ensure camera callbacks run on background handler to avoid blocking UI
                        cameraManager.openCamera(cameraId, stateCallback, mBackgroundHandler);
                    } else {
                        ActivityCompat.requestPermissions((FragmentActivity) context, new String[]{Manifest.permission.CAMERA}, 1);
                    }
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private Size choosePreviewSize(Size[] sizes, int reqW, int reqH) {
        if (sizes == null || sizes.length == 0) return new Size(reqW, reqH);
        // Prefer exact match
        for (Size s : sizes) {
            if (s.getWidth() == reqW && s.getHeight() == reqH) return s;
        }
        // Prefer same aspect ratio and largest area
        double target = (double) reqW / (double) reqH;
        Size best = null;
        double bestDiff = Double.MAX_VALUE;
        for (Size s : sizes) {
            double ratio = (double) s.getWidth() / (double) s.getHeight();
            double diff = Math.abs(ratio - target);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = s;
            } else if (Math.abs(diff - bestDiff) < 1e-3 && best != null) {
                // tie-breaker: choose larger size
                if (s.getWidth() * s.getHeight() > best.getWidth() * best.getHeight()) {
                    best = s;
                }
            }
        }
        return best != null ? best : sizes[0];
    }

    public void enablePreviewImageReader(boolean enable) {
        mEnablePreviewImageReader = enable;
    }

    public Size getPreviewSize() {
        if (mPreviewSize != null) return mPreviewSize;
        return new Size(this.mWidth == 0 ? 640 : this.mWidth, this.mHeight == 0 ? 480 : this.mHeight);
    }

    /**
     * 打开相机拍照
     *
     * @param // width         图片宽度
     * @param // height        图片高度
     * @param operationType 操作类型(支持OperationType.JPG、OperationType.VIDEO)
     * @param callBack
     */
    public void takePicture(OperationType operationType, TakePhotoCallBack callBack) {
        // if (captureSession == null || cameraDevice == null) {
        //     return;
        // }
        if (cameraDevice == null) {
            Log.e(TAG, "拍照失败：cameraDevice 为空！");
            if (callBack != null) callBack.error("cameraDevice is null");
            return;
        }
        if (mCaptureSession == null) {
            Log.e(TAG, "拍照失败：captureSession 尚未配置完成！");
            if (callBack != null) callBack.error("captureSession is null");
            return;
        }
        this.mCallBack = callBack;
        this.mOperationType = operationType;
        takePicture();
    }

    /**
     * 当前设备是否支持设置的分辨率
     *
     * @param width
     * @param height
     * @return
     */
    public boolean isSupportOutputSize(int width, int height) {
        Size[] supportOutputSize = getSupportOutputSize();
        boolean isSupportOutputSize = false;
        for (Size size : supportOutputSize) {
            if (width == size.getWidth() && height == size.getHeight()) {
                isSupportOutputSize = true;
                break;
            }
        }
        return isSupportOutputSize;
    }

    /**
     * 获取当前设备支持的拍照分辨率
     *
     * @return
     */
    public Size[] getSupportOutputSize() {
        StreamConfigurationMap streamConfigurationMap = mCameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (streamConfigurationMap != null) {
            Size[] outputSizes = streamConfigurationMap.getOutputSizes(ImageFormat.JPEG);
            for (Size size : outputSizes) {
                Log.i(TAG, "Supported resolution: " + size.getWidth() + "x" + size.getHeight());
            }
            return outputSizes;
        }
        return null;
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.i(TAG, "camera open time:" + (System.currentTimeMillis() - startTime));
            startTime = System.currentTimeMillis();
            cameraDevice = camera;
            // create default picture session
            //createCameraSession();
            // if a preview SurfaceTexture was requested earlier, start preview now
            if (mPendingPreviewSurfaceTexture != null) {
                try {
                    SurfaceTexture st = mPendingPreviewSurfaceTexture;
                    mPendingPreviewSurfaceTexture = null;
                    Log.d(TAG, "starting queued preview after camera opened");
                    startPreview(st);
                } catch (Exception e) {
                    Log.e(TAG, "failed to start queued preview", e);
                }
            }else{
                //createCameraSession();
                Log.d(TAG, "No queued preview to start");
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG," camera error: " + error);
            camera.close();
            cameraDevice = null;
        }
    };

    private void createCameraSession() {
        try {
            // close any existing session/reader before creating new ones to avoid state conflicts
            if (mCaptureSession != null) {
                try { mCaptureSession.close(); } catch (Exception ignored) {}
                mCaptureSession = null;
            }
            if (mPictureImageReader != null) {
                try { mPictureImageReader.close(); } catch (Exception ignored) {}
                mPictureImageReader = null;
            }
            int width = mWidth == 0 ? 640 : mWidth;
            int height = mHeight == 0 ? 480 : mHeight;
            mPictureImageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2); // 设置图片大小和格式
            mPictureImageReader.setOnImageAvailableListener(onImageAvailableListener, mBackgroundHandler);
            Surface surface = mPictureImageReader.getSurface();

            // 创建CaptureRequest.Builder
            CaptureRequest.Builder captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            /**
             * 相机捕捉图像的处理模式
             * CONTROL_MODE_AUTO 相机将根据环境条件自动调整多项设置，比如曝光时间(SENSOR_EXPOSURE_TIME)、感光度(SENSOR_SENSITIVITY/ISO)、白平衡(CONTROL_AWB_MODE)等，以期达到最佳成像效果。在此模式下，应用不需要也不应该直接设置这些参数。
             * CONTROL_MODE_USE_SCENE_MODE 这个模式允许使用预定义的场景模式（如风景、人像、夜景等），每个场景模式都有其特定的图像处理配置，适用于特定的拍摄环境。不过，实际应用中这个选项的使用可能比较有限，因为不是所有设备都支持所有的场景模式。
             * CONTROL_MODE_OFF 相机的自动控制功能被关闭，开发者需要手动设置所有相关的控制参数。这对于需要精确控制拍摄参数的应用特别有用
             */
            captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF);
            /**
             * 自动曝光的工作模式
             * CONTROL_AE_MODE_OFF 自动曝光功能是关闭的。相机的曝光参数（如快门速度、光圈大小、ISO感光度）需要手动设置
             * CONTROL_AE_MODE_ON  标准自动曝光模式。相机根据场景的光线条件自动调整曝光参数，以达到适中的整体亮度。这是最常用的自动曝光模式，适用于大多数日常拍摄情况。
             * CONTROL_AE_MODE_ON_AUTO_FLASH 自动曝光并启用闪光灯自动触发。当相机判断环境光线不足时，会自动激活闪光灯来补光，以改善画面亮度和细节。适合室内或者低光环境拍摄。
             * CONTROL_AE_MODE_ON_ALWAYS_FLASH 自动曝光模式下始终使用闪光灯。不论光线条件如何，每次拍照都会触发闪光灯。这在需要连续补光或特定创意效果时使用。
             * CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE 与CONTROL_AE_MODE_ON_AUTO_FLASH相似，但增加了减少红眼的功能。相机在使用闪光灯前会尝试通过预闪减少人像中的红眼现象，适合人像摄影。
             * CONTROL_AE_MODE_ON_EXTERNAL_FLASH 专为外部闪光灯设计的自动曝光模式。相机将与外部闪光灯设备配合，根据外部闪光灯的状态和场景光线自动调整曝光参数。
             */
            //captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);//自动曝光模式
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false); //允许相机调整曝光
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);//自动曝光并启用闪光灯自动触发
            captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 100000l);//设置曝光时间100000～133333333（单位：纳秒）
            captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, 2400);//设置感光度 32～2400
            /**
             * 自动白平衡的模式
             * AWB_MODE_OFF 关闭自动白平衡。此时，相机将使用手动设置的色温值（通过SENSOR_SENSITIVITY等参数）。这需要对摄影有深入了解，以确保在各种光线条件下都能得到正确的色彩表现。
             * AWB_MODE_AUTO 自动白平衡。相机自动检测并适应当前环境的光线色温，这是最常用的模式，适用于大多数场景，能够提供较为自然的色彩表现。
             * AWB_MODE_INCANDESCENT 专为白炽灯光设计的白平衡模式。白炽灯的光线色温较低，此模式会增加图像中的蓝色成分，以中和黄暖色调。
             * AWB_MODE_DAYLIGHT 适合日光环境的白平衡模式。在室外阳光下拍摄时使用，会减少图像中的黄色或红色成分，以保持自然的色彩平衡。
             * AWB_MODE_CLOUDY 适合阴天或遮蔽光照条件的白平衡模式。与日光模式相比，会增加一些暖色调，使画面看起来更加温暖自然。
             * AWB_MODE_TWILIGHT 专为黄昏或黎明时刻设计的白平衡模式，此时光线色温极低。此模式会显著增加图像中的蓝色和绿色成分，以补偿环境的暖色调。
             * AWB_MODE_SHADE 阴影模式，适用于物体处于直射阳光之外的阴影区域拍摄。会调整色彩平衡，减少冷色调，增加暖色调，使得阴影中的物体色彩更自然。
             */
            captureRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);//自动调整白平衡
            /**
             *  对焦模式设置
             *  CONTROL_AF_MODE_AUTO 相机根据场景自动选择合适的对焦模式（如单次对焦或连续对焦），适用于大多数常规拍摄情况。使用场景：通用拍摄模式，相机自动处理对焦逻辑，适合快速变化的场景。
             *  CONTROL_AF_MODE_MACRO 专为近距离拍摄设计的自动对焦模式，适用于微距摄影。当需要拍摄距离镜头很近的物体时使用，可以提高近距离对焦的准确性。
             *  CONTROL_AF_MODE_CONTINUOUS_PICTURE 为拍照设计的连续自动对焦模式，适用于静止图像拍摄，能够快速响应焦距的变化。静态摄影，尤其是当拍摄对象可能移动时，如人像或动物摄影。
             *  CONTROL_AF_MODE_EDOF 不是所有设备都支持，此模式通过算法模拟大景深效果，使得从近到远的多个距离上的物体都能保持相对清晰。使用场景：需要大景深效果的场景，如风景摄影。
             */
//            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);//连续对焦
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_MACRO);//近距离自动对焦模式
            captureRequestBuilder.addTarget(surface);

            /**
             * 增加曝光补偿，值范围通常从-10到10，具体范围可能因设备而异
             */
            //captureRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 2);
            // 创建CameraCaptureSession并捕获图像
            cameraDevice.createCaptureSession(Arrays.asList(surface), sessionStateCallback, mBackgroundHandler);
            Log.i(TAG, "camera createCameraSession time:" + (System.currentTimeMillis() - startTime));
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private final CameraCaptureSession.StateCallback sessionStateCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            mCaptureSession = session;
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
        }
    };

    public void takePicture() {
        try {
            CaptureRequest.Builder captureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureRequest.addTarget(mPictureImageReader.getSurface());
            mCaptureSession.capture(captureRequest.build(), captureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            Log.d(TAG, "图片已捕获");
            //closeCamera(); // 捕获完成后关闭相机
        }
    };

    // lightweight preview capture callback to log frame callbacks
    private final CameraCaptureSession.CaptureCallback mPreviewCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
            super.onCaptureStarted(session, request, timestamp, frameNumber);
            Log.v(TAG, "preview onCaptureStarted ts=" + timestamp + " frame=" + frameNumber + " thread=" + Thread.currentThread().getName());
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            Log.v(TAG, "preview onCaptureCompleted thread=" + Thread.currentThread().getName());
        }

        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
            super.onCaptureFailed(session, request, failure);
            Log.w(TAG, "preview onCaptureFailed reason=" + failure.getReason() + " frame=" + failure.getFrameNumber());
        }
    };

    private final ImageReader.OnImageAvailableListener onImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            startTime = System.currentTimeMillis();
            Image image = reader.acquireLatestImage();
            if (image != null) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        handleImage(image);
                        Log.d(TAG, "handle image time: " + (System.currentTimeMillis() - startTime));
//                        if (mImageFormat == ImageFormat.YUV_420_888) {
//                            saveYuvData(image);
//                            image.close();
//                            return;
//                        }
//                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
//                        byte[] bytes = new byte[buffer.capacity()];
//                        buffer.get(bytes);
//                        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
//                        saveBitmapToStorage(rotateBitmap(bitmap, deviceRotationDegrees));
//                        Log.i(TAG, "save image time: " + (System.currentTimeMillis() - startTime));
                        image.close();
                    }
                }).start();
            } else {
                Log.e(TAG, "onImageAvailable image is null");
            }
        }
    };

    /**
     * 处理图像数据（保存返回path还是直接返回byte[]）
     *
     * @param image
     */
    private void handleImage(Image image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.capacity()];
        buffer.get(bytes);
        if(mOperationType == OperationType.JPG){
            mCallBack.finishByte(bytes);
        }else{
            byte[] yuvData = YUVUtil.convertJpgToYUV420P(bytes,image.getWidth(),image.getHeight(),true);
            mCallBack.finishByte(yuvData);
        }
    }

    /**
     * 保存图片到本地
     *
     * @param bitmap
     * @throws IOException
     */
    private void saveBitmapToStorage(Bitmap bitmap) {
        path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES) + File.separator + System.currentTimeMillis() + ".jpg";

        File file = new File(path);
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos); // 这里的50是压缩质量
            mCallBack.finishPath(path);
            Log.i(TAG, "saveBitmapToStorage success");
        } catch (Exception e) {
            Log.e(TAG, "saveBitmapToStorage error:" + e.getMessage());
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    Log.e(TAG, "saveBitmapToStorage error:" + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 保存图片
     *
     * @param bytes
     */
    private void saveImage(byte[] bytes) {
        path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES) + File.separator + System.currentTimeMillis() + ".jpg";
        File file = new File(path);
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(file);
            output.write(bytes);
            mCallBack.finishPath(path);
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, "save error" + e.getMessage());
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException e) {
                    e.printStackTrace();
                    Log.e(TAG, "save error" + e.getMessage());
                }
            }
        }
    }


    private void closeCamera() {
        if (null != mCaptureSession) {
            mCaptureSession.close();
            mCaptureSession = null;
        }
        if (null != cameraDevice) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (null != mPictureImageReader) {
            mPictureImageReader.close();
            mPictureImageReader = null;
        }
    }

    public void destroy() {
        closeCamera();
        if (backgroundThread != null) {
            backgroundThread.interrupt();
            backgroundThread.quitSafely();
            backgroundThread = null;
            mBackgroundHandler = null;
        }
    }

    private String getFacingCameraId(android.hardware.camera2.CameraManager cameraManager) {
        try {
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                switch (facing) {
                    case CameraCharacteristics.LENS_FACING_BACK:
                        Log.d(TAG, "后置摄像头: " + cameraId);
                        break;
                    case CameraCharacteristics.LENS_FACING_FRONT:
                        Log.d(TAG, "前置摄像头: " + cameraId);
                        break;
                    default:
                        Log.d(TAG, "其他摄像头类型: " + cameraId);
                        break;
                }
                if (facing != null) {
                    return cameraId;
                }
//                if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
//                    Log.d(TAG, "外置摄像头: " + cameraId);
//                    return cameraId;
//                }

            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "getBackFacingCameraId error:" + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 旋转图片方向
     *
     * @param bitmap
     * @param rotationDegrees
     * @return
     */
    private Bitmap rotateBitmap(Bitmap bitmap, int rotationDegrees) {
        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    /**
     * 通过设备的传感器角度判断照片应该旋转的角度
     *
     * @param context
     * @param characteristics
     * @return
     */
    private int getDeviceRotationDegrees(Context context, CameraCharacteristics characteristics) {
        int rotation = context.getSystemService(WindowManager.class).getDefaultDisplay().getRotation();
        int sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        switch (rotation) {
            case Surface.ROTATION_0:
                return sensorOrientation;
            case Surface.ROTATION_90:
                return (sensorOrientation + 90) % 360;
            case Surface.ROTATION_180:
                return (sensorOrientation + 180) % 360;
            case Surface.ROTATION_270:
                return (sensorOrientation + 270) % 360;
            default:
                throw new IllegalArgumentException("Invalid rotation value");
        }
    }


    public interface TakePhotoCallBack {
        /**
         * 回调图片路径
         *
         * @param path
         */
        void finishPath(String path);

        /**
         * 回调拍照过程异常
         *
         * @param msg
         */
        void error(String msg);

        /**
         * 回调捕捉图像字节数组
         *
         * @param data
         */
        void finishByte(byte[] data);
    }

    private void saveYuvData(byte[] bytes) {
        path = context.getFilesDir() + File.separator + System.currentTimeMillis() + ".yuv";
        File file = new File(path);
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(file);
            output.write(bytes);
            mCallBack.finishPath(path);
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, "save error" + e.getMessage());
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException e) {
                    e.printStackTrace();
                    Log.e(TAG, "save error" + e.getMessage());
                }
            }
        }
    }
    public enum OperationType {
        JPG(1, "jpg"),
        VIDEO(2, "video");

        OperationType(int type, String desc) {
            this.type = type;
            this.desc = desc;
        }

        private int type;
        private String desc;
    }

    private boolean isOpen() {
        return cameraDevice != null;
    }

    public void startPreview(SurfaceTexture surfaceTexture) {
        //预留接口，后续支持预览功能
        Log.d(TAG, "startPreview called; isOpen=" + isOpen() + ", previewing=" + previewing);
        // if camera not yet opened, save the surfaceTexture and start preview later in onOpened()
        if (!isOpen()) {
            mPendingPreviewSurfaceTexture = surfaceTexture;
            Log.d(TAG, "startPreview queued pending SurfaceTexture");
            return;
        }
        if (previewing) return;
        previewing = true;
        Log.d(TAG,"is previewing: " + previewing);
        // Use the selected preview size for buffer
        Size ps = getPreviewSize();
        surfaceTexture.setDefaultBufferSize(ps.getWidth(), ps.getHeight());
        mPreviewSurface = new Surface(surfaceTexture);
        initPreviewRequest();
        createCommonSession();
    }

    private void initPreviewRequest() {
        //预留接口，后续支持预览功能
        if (mPreviewSurface == null) {
            Log.e(TAG, "initPreviewRequest failed, mPreviewSurface is null");
            return;
        }
        if (!isOpen()) {
            return;
        }
        try{
            mPreviewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            // 设置连续自动对焦
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            // yunxuzidongbaoguang
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false);
            // 设置自动曝光
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            // 设置自动白平衡
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
            // 设置预览输出的 Surface
            mPreviewRequestBuilder.addTarget(mPreviewSurface);
        }catch (CameraAccessException e){
            Log.e(TAG, "initPreviewRequest error:" + e.getMessage());
            e.printStackTrace();
        }

    }

    private void createCommonSession() {
        // if an existing session/reader exists, close them first to avoid resource conflicts
        if (mCaptureSession != null) {
            try { mCaptureSession.close(); } catch (Exception ignored) {}
            mCaptureSession = null;
        }
        if (mPictureImageReader != null) {
            try { mPictureImageReader.close(); } catch (Exception ignored) {}
            mPictureImageReader = null;
        }

        List<Surface> outputs = new ArrayList<>();
        // preview output
        if (mPreviewSurface != null) {
            Log.d(TAG, "createCommonSession add target mPreviewSurface");
            outputs.add(mPreviewSurface);
        }
        // picture output
        Size pictureSize = null; // mPictureSize is set during initialization
        if (pictureSize != null) {
            Log.d(TAG, "createCommonSession add target mPictureImageReader");
            mPictureImageReader = ImageReader.newInstance(pictureSize.getWidth(), pictureSize.getHeight(), ImageFormat.JPEG, 1);
            outputs.add(mPictureImageReader.getSurface());
        }

        // preview output TODO: CHECK PREVIEW OUTPUT
        if (mEnablePreviewImageReader) {
            Log.d(TAG, "createCommonSession add target mPreviewImageReader==========");
            mPreviewImageReader = ImageReader.newInstance(mPreviewSize.getWidth(), mPreviewSize.getHeight(), ImageFormat.YUV_420_888, 2);
            mPreviewImageReader.setOnImageAvailableListener(new OnImageAvailableListenerImpl(), mBackgroundHandler);
            outputs.add(mPreviewImageReader.getSurface());
            mPreviewRequestBuilder.addTarget(mPreviewImageReader.getSurface());
        }

        try {
            Log.d(TAG, "createCommonSession: outputs=" + outputs.size());
            for (Surface s : outputs) {
                Log.d(TAG, " createCommonSession output surface: " + s);
            }
            // 一个session中，所有CaptureRequest能够添加的target，必须是outputs的子集，所以在创建session的时候需要都添加进来
            cameraDevice.createCaptureSession(outputs, new CameraCaptureSession.StateCallback() {

                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    Log.d(TAG, "createCommonSession.onConfigured thread=" + Thread.currentThread().getName());
                    mCaptureSession = session;
                    startPreviewED();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "ConfigureFailed. session: " + session);
                    previewing = false;
                }
            }, mBackgroundHandler); // handle 传入 null 表示使用当前线程的 Looper
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // simple preview image listener to avoid leaking preview Image objects
    private class OnImageAvailableListenerImpl implements ImageReader.OnImageAvailableListener {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null) return;
                // currently we don't process preview frames here; consuming/closing prevents buffer starvation
            } catch (Exception e) {
                Log.e(TAG, "OnImageAvailableListenerImpl error: " + e.getMessage());
            } finally {
                if (image != null) {
                    image.close();
                }
            }
        }
    }

    private void startPreviewED() {
        Log.v(TAG, "startPreview");
        if (mCaptureSession == null || mPreviewRequestBuilder == null) {
            Log.w(TAG, "startPreview: mCaptureSession or mPreviewRequestBuilder is null");
            return;
        }
        try {
            // 开始预览，即一直发送预览的请求
            CaptureRequest captureRequest = mPreviewRequestBuilder.build();
            /*
            CHECK
            */
            boolean isValid = mPreviewSurface.isValid();
            if (!isValid) {
                Log.e(TAG, "检测到尝试向已销毁的 Surface 发送指令！这将导致 HAL 崩溃！");
                return; 
            }
            mCaptureSession.setRepeatingRequest(captureRequest, mPreviewCaptureCallback, mBackgroundHandler);
            Log.i(TAG, "name:" + Thread.currentThread().getName());
            // Log.i(TAG, "mCaptureSession: " + mCaptureSession);
            // ensure preview dimensions are set before notifying UI
            if (mPreviewSize != null) {
                mPreviewWidth = mPreviewSize.getWidth();
                mPreviewHeight = mPreviewSize.getHeight();
            } else {
                mPreviewWidth = this.mWidth == 0 ? 640 : this.mWidth;
                mPreviewHeight = this.mHeight == 0 ? 480 : this.mHeight;
            }
            if (mUIHandler != null) {
                mUIHandler.post(() -> onPreview(mPreviewWidth, mPreviewHeight));
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // stub callback when preview starts; extend to notify listeners as needed
    private void onPreview(int width, int height) {
        Log.d(TAG, "onPreview: " + width + "x" + height);
    }

}
