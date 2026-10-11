package com.lyqsmart.my_radio;



import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.List;

/**
 * 电台列表适配器。
 * 用于在 ListView 中显示扫描到的电台，支持高亮当前选中的电台。
 */
public class StationAdapter extends ArrayAdapter<Station> {

    private Context context;
    private List<Station> stations;
    private int selectedPosition = -1;   // 当前选中的电台，-1 表示无

    public StationAdapter(Context context, List<Station> stations) {
        super(context, 0, stations);
        this.context = context;
        this.stations = stations;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_station, parent, false);
        }

        Station s = stations.get(position);

        TextView tvFreq  = convertView.findViewById(R.id.tv_item_freq);
        TextView tvRssi  = convertView.findViewById(R.id.tv_item_rssi);
        TextView tvLevel = convertView.findViewById(R.id.tv_item_level);

        tvFreq.setText(s.getDisplayFreq());
        tvRssi.setText(s.getDisplayRssi());
        tvLevel.setText(s.getLevel());

        // 根据强度设置颜色
        if (s.getRssi() >= 15) {
            tvLevel.setTextColor(Color.parseColor("#4CAF50"));   // 绿
        } else if (s.getRssi() >= 8) {
            tvLevel.setTextColor(Color.parseColor("#FF9800"));   // 橙
        } else {
            tvLevel.setTextColor(Color.parseColor("#9E9E9E"));   // 灰
        }

        // 高亮当前选中的电台
        if (position == selectedPosition) {
            convertView.setBackgroundColor(Color.parseColor("#E3F2FD"));
            tvFreq.setTextColor(Color.parseColor("#2196F3"));
            tvFreq.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            convertView.setBackgroundColor(Color.TRANSPARENT);
            tvFreq.setTextColor(Color.parseColor("#1A1A1A"));
            tvFreq.setTypeface(null, android.graphics.Typeface.NORMAL);
        }

        return convertView;
    }

    /**
     * 设置当前选中的电台位置，用于高亮显示。
     */
    public void setSelectedPosition(int position) {
        this.selectedPosition = position;
        notifyDataSetChanged();
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    /**
     * 根据频率文本查找位置，返回 -1 表示没找到。
     */
    public int findPositionByFreq(String freqText) {
        for (int i = 0; i < stations.size(); i++) {
            if (stations.get(i).getFreqText().equals(freqText)) {
                return i;
            }
        }
        return -1;
    }
}