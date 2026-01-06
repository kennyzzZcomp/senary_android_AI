package com.tongyi.multimodal_dialog.utils;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class AssetUtil {

    public static byte[] getImageBytesFromAssets(Context context, String fileName) {
        AssetManager assetManager = context.getAssets();
        ByteArrayOutputStream byteArrayOutputStream = null;

        try (InputStream inputStream = assetManager.open(fileName)) {
            byteArrayOutputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                byteArrayOutputStream.write(buffer, 0, bytesRead);
            }
            return byteArrayOutputStream.toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        } finally {
            if (byteArrayOutputStream != null) {
                try {
                    byteArrayOutputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static byte[] readPcmDataFromWav(Context context, String fileName, int start, int end) {
        byte[] pcmData = null;
        InputStream inputStream = null;
        try {
            // 打开 assets 文件夹中的 WAV 文件
            inputStream = context.getAssets().open(fileName);

            // 跳过 WAV 头部（通常为44字节）并移动到指定开始位置
            int headerSize = 44;
            inputStream.skip(headerSize + start);

            // 计算需要读取的字节数
            int lengthToRead = end - start;

            if (lengthToRead > 0) {
                pcmData = new byte[lengthToRead];
                int bytesRead = inputStream.read(pcmData, 0, lengthToRead);

                if (bytesRead != lengthToRead) {
                    // 处理读取长度不足的情况
                    Log.e("AssetUtil", "Failed to read all bytes from WAV file.");
                    return null;
                }
            } else {
                throw new IllegalArgumentException("End position must be greater than start position.");
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return pcmData;
    }

    /**
     * 读取本地文件并返回其内容的字节数组
     * @param filePath 文件路径
     * @return 文件内容的字节数组
     * @throws IOException 当文件读取出错时抛出
     */
    public static byte[] readFileToByteArray(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            throw new IOException("File not found or is not a file.");
        }

        FileInputStream fis = null;
        byte[] data = new byte[(int) file.length()];
        try {
            fis = new FileInputStream(file);
            int bytesRead = fis.read(data);
            if (bytesRead != data.length) {
                throw new IOException("File reading error.");
            }
        } finally {
            if (fis != null) {
                fis.close();
            }
        }

        return data;
    }
}

