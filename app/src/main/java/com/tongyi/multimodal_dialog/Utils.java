package com.tongyi.multimodal_dialog;

public class Utils {

    public static int msToBytes(int ms, int sr) {
        return sr / 1000 * 2 * ms;
    }
    public static int bytesToMs(int bytes, int sr) {
        return bytes / (sr / 1000) / 2;
    }

    // 计算给定PCM音频数据的RMS值
    private static int calculateRMSLevel(byte[] audioData) {
        // 将byte数组转换为short数组（假设是16位PCM，小端序）
        short[] shorts = new short[audioData.length / 2];
        for (int i = 0; i < shorts.length; i++) {
            shorts[i] = (short) ((audioData[i * 2] & 0xFF) | (audioData[i * 2 + 1] << 8));
        }

        // 计算平均平方值
        double rms = 1.0;
        for (short s : shorts) {
            rms += (double)Math.abs(s);
        }
        rms = rms / shorts.length;

        // 计算分贝值
        double db = 20 * Math.log10(rms);
        db = db * 160 / 90 - 160;
        if (db > 0.0) {
            db = 0.0;
        } else if (db < -160.0) {
            db = -160;
        }

        // level值
        int level = (int)((db + 160) * 100 / 160);
        return level;
    }
    /**
     * 一个简单的哈希函数，用于生成唯一的闹钟 ID。
     */
    public static int generateAlarmId(String time, String date, String content, String repeat) {
        // 组合关键信息生成哈希值
        String uniqueString = date + "|" + time + "|" + content + "|" + repeat + "|" + System.currentTimeMillis();
        return Math.abs(uniqueString.hashCode());
    }
}
