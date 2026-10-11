package com.lyqsmart.my_radio;


/**
 * 电台数据模型。
 * 表示扫描到的一个电台：频率、RSSI、强度等级。
 */
public class Station {

    private String freqText;    // 显示用，如 "89.60"
    private int freqX10;        // 发送命令用，如 8960
    private int rssi;           // RSSI 值 0~127
    private String level;       // "强" / "中" / "弱"

    public Station(String freqText, int freqX10, int rssi) {
        this.freqText = freqText;
        this.freqX10 = freqX10;
        this.rssi = rssi;
        if (rssi >= 15)      this.level = "强";
        else if (rssi >= 8)  this.level = "中";
        else                 this.level = "弱";
    }

    public String getFreqText() { return freqText; }
    public int getFreqX10()     { return freqX10; }
    public int getRssi()        { return rssi; }
    public String getLevel()    { return level; }

    /** 显示用：89.60 MHz */
    public String getDisplayFreq() {
        return freqText + " MHz";
    }

    /** 显示用：RSSI=12 */
    public String getDisplayRssi() {
        return "RSSI=" + rssi;
    }
}