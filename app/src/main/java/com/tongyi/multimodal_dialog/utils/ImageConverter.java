package com.tongyi.multimodal_dialog.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class ImageConverter {
    public static byte[] convertJpegToYuv420P(String imagePath) throws IOException {
        // Load the JPEG image from the specified path
        File file = new File(imagePath);
        FileInputStream fis = new FileInputStream(file);
        Bitmap bitmap = BitmapFactory.decodeStream(fis);

        // Get the dimensions of the Bitmap
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        // Create a byte array to hold the YUV420P data
        int yuvSize = width * height * 3 / 2;
        byte[] yuvBytes = new byte[yuvSize];

        // Convert Bitmap to YUV420P format
        encodeYUV420P(yuvBytes, bitmap, width, height);

        // Close resources
        fis.close();
        bitmap.recycle();

        return yuvBytes;
    }

    private static void encodeYUV420P(byte[] yuv420p, Bitmap bmp, int width, int height) {
        int frameSize = width * height;
        int[] argb = new int[width * height];
        bmp.getPixels(argb, 0, width, 0, 0, width, height);

        int yIndex = 0;
        int uIndex = frameSize;
        int vIndex = frameSize + (frameSize / 4);

        int a, R, G, B, Y, U, V;
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                a = (argb[j * width + i] & 0xff000000) >> 24; // alpha
                // No need to use alpha, since it will be lost in the conversion anyway
                R = (argb[j * width + i] & 0xff0000) >> 16;
                G = (argb[j * width + i] & 0xff00) >> 8;
                B = (argb[j * width + i] & 0xff);

                // Conversion to YUV
                Y = ((66 * R + 129 * G + 25 * B) >> 8) + 16;
                U = ((-38 * R - 74 * G + 112 * B) >> 8) + 128;
                V = ((112 * R - 94 * G - 18 * B) >> 8) + 128;

                // Clip Y and UV values to 0-255
                Y = Math.max(0, Math.min(Y, 255));
                U = Math.max(0, Math.min(U, 255));
                V = Math.max(0, Math.min(V, 255));

                // Assign the Y value
                yuv420p[yIndex++] = (byte) Y;

                // Assign UV values at subsampled positions
                if ((j % 2 == 0) && (i % 2 == 0)) {
                    yuv420p[uIndex++] = (byte) U;
                    yuv420p[vIndex++] = (byte) V;
                }
            }
        }
    }
}
