package com.lyqsmart.my_radio;



import android.content.Context;
import android.content.SharedPreferences;

/**
 * APP 设置管理。
 * 目前保存：
 *   - 频率步进（10kHz 单位，默认 10 = 100kHz）
 *   - 上次连接的 MAC 地址
 */
public class Settings {

    private static final String SP_NAME = "RadioSettings";
    private static final String KEY_FREQ_STEP = "freq_step";
    private static final String KEY_LAST_MAC = "last_mac";

    private SharedPreferences sp;

    public Settings(Context context) {
        sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    /** 频率步进，10kHz 单位。默认 10 = 100kHz */
    public int getFreqStep() {
        return sp.getInt(KEY_FREQ_STEP, 10);
    }

    public void setFreqStep(int step) {
        sp.edit().putInt(KEY_FREQ_STEP, step).apply();
    }

    /** 上次连接的 MAC 地址 */
    public String getLastMac() {
        return sp.getString(KEY_LAST_MAC, null);
    }

    public void setLastMac(String mac) {
        sp.edit().putString(KEY_LAST_MAC, mac).apply();
    }

    /** 步进的可选值（显示名 + 值） */
    public static final String[] STEP_NAMES = {"50 kHz", "100 kHz", "200 kHz"};
    public static final int[]    STEP_VALUES = {5, 10, 20};

    /** 根据值返回在 STEP_NAMES 中的下标，用于 Spinner 初始化 */
    public int getStepIndex() {
        int v = getFreqStep();
        for (int i = 0; i < STEP_VALUES.length; i++) {
            if (STEP_VALUES[i] == v) return i;
        }
        return 1;   // 默认 100kHz
    }
}