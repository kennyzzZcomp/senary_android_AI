package com.tongyi.multimodal_dialog.utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class LogcatWriter {
    private static final String TAG = "LogcatWriter";
    private static boolean isRunning = true;

    public static void endLogcat() {
        isRunning = false;
    }

    public static void writeLogcatToFile(String filePath) {
        isRunning = true;
        new Thread(() -> {
            try {
                // 创建File对象，用于存储日志文件
                File logFile = new File(filePath);

                // 如果文件已存在，删除它
                if (logFile.exists()) {
                    logFile.delete();
                }

                // 创建文件输出流
                FileOutputStream fos = new FileOutputStream(logFile);

                // 执行logcat命令
                Process process = Runtime.getRuntime().exec("logcat -f " + logFile.getAbsolutePath());
//                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()), 81920);
//
//                // 读取logcat的输出内容
//                String line = "";
//                while (isRunning) {
//                    line = bufferedReader.readLine();
//                    if (line != null) {
//                        // 将内容写入文件
//                        // 将日志内容写入文件
//                        Log.d(TAG, line);
//                        fos.write((line + "\n").getBytes());
//                    }else {
//                        Thread.sleep(100);
//                    }
//                }
//                Log.i(TAG, "LogcatWriter end");
//
//                // 关闭流
//                bufferedReader.close();
//                fos.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();

    }
}