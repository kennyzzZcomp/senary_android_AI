package com.tongyi.multimodal_dialog.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

/**
 * @program:artcTest
 * @description: YUV数据处理工具类
 * @author: yuyong
 * @date: 2024-11-18 11:17
 **/
public class YUVUtil {
    public static byte[] convertJpgToYUV420P(byte[] jpgData, int width, int height, boolean isRotate) {
        // Decode JPG to Bitmap
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpgData, 0, jpgData.length);

        if (bitmap == null) {
            throw new IllegalArgumentException("Could not decode image");
        }

        if(isRotate){//旋转90度
            Matrix matrix = new Matrix();
            matrix.postRotate(90.f);
            bitmap = Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);

        }

        int[] argb = new int[width * height];
        if(isRotate){
            bitmap.getPixels(argb, 0, height, 0, 0, height, width);
        }else{
            bitmap.getPixels(argb, 0, width, 0, 0, width, height);
        }


        byte[] yuv = new byte[width * height * 3 / 2];
        if(isRotate){
            encodeYUV420P(yuv, argb, height, width);
        }else{
            encodeYUV420P(yuv, argb, width, height);
        }

        bitmap.recycle();

        return yuv;
    }

    private static void encodeYUV420P(byte[] yuv420p, int[] argb, int width, int height) {
        final int frameSize = width * height;
        int yIndex = 0;
        int uIndex = frameSize;
        int vIndex = frameSize + frameSize / 4;

        int R, G, B, Y, U, V;
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                // Get ARGB values
                int argbIndex = j * width + i;
                int a = (argb[argbIndex] & 0xff000000) >> 24;
                int r = (argb[argbIndex] & 0xff0000) >> 16;
                int g = (argb[argbIndex] & 0xff00) >> 8;
                int b = (argb[argbIndex] & 0xff);

                // Convert to YUV
                R = r;
                G = g;
                B = b;

                Y = ((66 * R + 129 * G + 25 * B + 128) >> 8) + 16;
                U = ((-38 * R - 74 * G + 112 * B + 128) >> 8) + 128;
                V = ((112 * R - 94 * G - 18 * B + 128) >> 8) + 128;

                // Clip values to the 0-255 range
                Y = Math.max(0, Math.min(Y, 255));
                U = Math.max(0, Math.min(U, 255));
                V = Math.max(0, Math.min(V, 255));

                // Output YUV values
                yuv420p[yIndex++] = (byte) Y;
                if (j % 2 == 0 && i % 2 == 0) {
                    yuv420p[uIndex++] = (byte) U;
                    yuv420p[vIndex++] = (byte) V;
                }
            }
        }
    }

}

