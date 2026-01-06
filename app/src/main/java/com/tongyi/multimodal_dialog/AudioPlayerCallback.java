package com.tongyi.multimodal_dialog;

public interface AudioPlayerCallback {
    public enum PlayerState {
        headset_plug,
        headset_unplug;

        public static int toInt(PlayerState state) {
            switch (state) {
                case headset_plug:
                    return 0;
                case headset_unplug:
                    return 1;
                default:
                    return 0;
            }
        }
    }

    public void playStart();
    public void playOver(boolean interrupt, int delay_ms);
    public void playSoundLevel(int level);
    public void showLog(String text, String tag);
    public int onPlayerData(byte[] data, int len);
    public void playerStateChanged(int action);
}
