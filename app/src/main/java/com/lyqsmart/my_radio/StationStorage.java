package com.lyqsmart.my_radio;
import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * 电台列表持久化。
 * 用 SharedPreferences + Gson，把 List<Station> 序列化成 JSON 保存。
 */
public class StationStorage {

    private static final String SP_NAME = "RadioStations";
    private static final String KEY_STATIONS = "stations";

    private SharedPreferences sp;
    private Gson gson = new Gson();

    public StationStorage(Context context) {
        sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    /** 保存电台列表 */
    public void save(List<Station> stations) {
        String json = gson.toJson(stations);
        sp.edit().putString(KEY_STATIONS, json).apply();
    }

    /** 加载电台列表，没有则返回空列表 */
    public List<Station> load() {
        String json = sp.getString(KEY_STATIONS, null);
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            Type type = new TypeToken<List<Station>>(){}.getType();
            List<Station> list = gson.fromJson(json, type);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 清空保存的电台 */
    public void clear() {
        sp.edit().remove(KEY_STATIONS).apply();
    }
}