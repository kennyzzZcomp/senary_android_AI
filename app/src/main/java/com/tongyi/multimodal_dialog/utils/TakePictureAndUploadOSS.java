package com.tongyi.multimodal_dialog.utils;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.util.Log;

import com.alibaba.sdk.android.oss.ClientException;
import com.alibaba.sdk.android.oss.OSSClient;
import com.alibaba.sdk.android.oss.ServiceException;
import com.alibaba.sdk.android.oss.callback.OSSCompletedCallback;
import com.alibaba.sdk.android.oss.callback.OSSProgressCallback;
import com.alibaba.sdk.android.oss.common.HttpMethod;
import com.alibaba.sdk.android.oss.common.auth.OSSCredentialProvider;
import com.alibaba.sdk.android.oss.common.auth.OSSPlainTextAKSKCredentialProvider;
import com.alibaba.sdk.android.oss.internal.OSSAsyncTask;
import com.alibaba.sdk.android.oss.model.GeneratePresignedUrlRequest;
import com.alibaba.sdk.android.oss.model.PutObjectRequest;
import com.alibaba.sdk.android.oss.model.PutObjectResult;
import com.tongyi.multimodal_dialog.oss.OssParam;
import com.tongyi.multimodal_dialog.oss.OssUploadCallBack;

public class TakePictureAndUploadOSS {

    public interface TakePictureAndUploadOSSCallback {
        public void ossSuccess(String url);
    }
    private static final String TAG = TakePictureAndUploadOSS.class.getName();
    private HandlerThread mTakePictureThread;
    private Handler mTakePictureHandler;

    private TakePictureAndUploadOSSCallback callback;
    private Context context;

    public TakePictureAndUploadOSS(Context context, TakePictureAndUploadOSSCallback callback) {
        this.context = context;
        this.callback = callback;
    }

    private void initTakePictureHandler(){
        if(mTakePictureThread == null){
            mTakePictureThread = new HandlerThread("takePictureThread");
            mTakePictureThread.start();
        }
        if(mTakePictureHandler == null){
            mTakePictureHandler = new Handler(mTakePictureThread.getLooper());
        }
    }

    /**
     * 拍照上传
     */
    public void takePicture(){
        initTakePictureHandler();
        mTakePictureHandler.post(new Runnable() {
            @Override
            public void run() {
                CameraManager.getInstance().takePicture(CameraManager.OperationType.JPG,new CameraManager.TakePhotoCallBack() {
                    @Override
                    public void finishPath(String path) {
                        Log.i(TAG, "finishPath: " + path);
                    }

                    @Override
                    public void error(String msg) {
                        Log.i(TAG, "error: " + msg);
                        //拍照失败
                    }

                    @Override
                    public void finishByte(byte[] data) {
                        boolean useOSS = true;
                        if (useOSS){
                            uploadVQAImageByOSS(data);
                        }
                    }
                });
            }
        });

    }

    private void uploadVQAImageByOSS(byte[] imageData) {
        OssParam ossParam = new OssParam();
        ossParam.setEndpoint("https://oss-cn-beijing.aliyuncs.com");
        ossParam.setAccessKeyId("");  // 以下几个参数根据OSS控制台配置获取
        ossParam.setAccessKeySecret("");
        ossParam.setBuckName("");
        ossParam.setObjectKey("moc/" + System.currentTimeMillis() + ".jpg");
        ossParam.setData(imageData);

        uploadImage(this.context, ossParam, new OssUploadCallBack() {
            @Override
            public void success(String url) {
                Log.i(TAG, "uploadOSS: success " + url);
                callback.ossSuccess(url);

            }
            @Override
            public void fail(ClientException clientException, ServiceException serviceException) {
                Log.i(TAG, "uploadOSS: fail ");
            }
        });
    }

    private void uploadImage(Context context, OssParam ossParam, OssUploadCallBack callBack) {
        if (null == context
                || ossParam == null
                || TextUtils.isEmpty(ossParam.getEndpoint())
                || TextUtils.isEmpty(ossParam.getAccessKeyId())
                || TextUtils.isEmpty(ossParam.getAccessKeySecret())
                || TextUtils.isEmpty(ossParam.getBuckName())
                || TextUtils.isEmpty(ossParam.getObjectKey())) {
            Log.e(TAG, "uploadImage param error");
            return;
        }
        if (ossParam.getData() == null && TextUtils.isEmpty(ossParam.getFilePath())) {
            Log.e(TAG, "uploadImage data is null");
            return;
        }
        long startTime = System.currentTimeMillis();
        OSSCredentialProvider credentialProvider = new OSSPlainTextAKSKCredentialProvider(ossParam.getAccessKeyId(), ossParam.getAccessKeySecret());

        // 创建OSSClient实例。
        OSSClient oss = new OSSClient(context.getApplicationContext(), ossParam.getEndpoint(), credentialProvider);

        // 构造上传请求。
        PutObjectRequest put = null;
        if (ossParam.getData() != null && ossParam.getData().length > 0) {
            put = new PutObjectRequest(ossParam.getBuckName(), ossParam.getObjectKey(), ossParam.getData());
        } else {
            put = new PutObjectRequest(ossParam.getBuckName(), ossParam.getObjectKey(), ossParam.getFilePath());
        }

        // 异步上传时可以设置进度回调。
        put.setProgressCallback(new OSSProgressCallback<PutObjectRequest>() {
            @Override
            public void onProgress(PutObjectRequest request, long currentSize, long totalSize) {
                Log.d("PutObject", "currentSize: " + currentSize + " totalSize: " + totalSize);
            }
        });

        OSSAsyncTask task = oss.asyncPutObject(put, new OSSCompletedCallback<PutObjectRequest, PutObjectResult>() {
            @Override
            public void onSuccess(PutObjectRequest request, PutObjectResult result) {
                Log.d(TAG, "upload Success time: " + (System.currentTimeMillis() - startTime));
                callBack.success(getFileUrl(ossParam, oss));
            }

            @Override
            public void onFailure(PutObjectRequest request, ClientException clientException, ServiceException serviceException) {
                // 请求异常。
                if (clientException != null) {
                    // 本地异常，如网络异常等。
                    clientException.printStackTrace();
                }
                if (serviceException != null) {
                    // 服务异常。
                    Log.e("ErrorCode", serviceException.getErrorCode());
                    Log.e("RequestId", serviceException.getRequestId());
                    Log.e("HostId", serviceException.getHostId());
                    Log.e("RawMessage", serviceException.getRawMessage());
                }
                callBack.fail(clientException, serviceException);
            }
        });
        // task.cancel(); // 可以取消任务。
        // task.waitUntilFinished(); // 等待上传完成。
    }

    private String getFileUrl(OssParam ossParam, OSSClient oss) {
        long startTime = System.currentTimeMillis();
        String url = null;
        try {
            // 生成用于下载文件的签名URL。
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(ossParam.getBuckName(), ossParam.getObjectKey());
            // 设置签名URL的过期时间。
            if (ossParam.getExpiration() == null) {
                ossParam.setExpiration(12 * 60 * 60L);
            } else {
                if (ossParam.getExpiration() < 30 * 60L) {
                    ossParam.setExpiration(30 * 60L);
                }
                if (ossParam.getExpiration() > 12 * 60 * 60L) {
                    ossParam.setExpiration(12 * 60 * 60L);
                }
            }
            request.setExpiration(ossParam.getExpiration());
            request.setMethod(HttpMethod.GET);
            url = oss.presignConstrainedObjectURL(request);
            Log.d(TAG, "file url:" + url);
            Log.d(TAG, "getFileUrl time: " + (System.currentTimeMillis() - startTime));
            return url;
        } catch (ClientException e) {
            Log.e(TAG, "getFileUrl error:" + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }
}
