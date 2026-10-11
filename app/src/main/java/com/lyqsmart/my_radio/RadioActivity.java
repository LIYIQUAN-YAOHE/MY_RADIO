package com.lyqsmart.my_radio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@SuppressLint("MissingPermission")
public class RadioActivity extends AppCompatActivity {

    private static final String TAG = "RadioActivity";

    private static final UUID SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID CHARACTERISTIC_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final UUID DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int REQ_PERM = 101;
    private static final int REQ_ENABLE_BT = 102;

    private static final String SP_NAME = "RadioPrefs";
    private static final String KEY_LAST_MAC = "last_mac";
    private static final String KEY_FREQ_STEP = "freq_step";
    private static final String KEY_STATIONS = "stations";

    // ---------- UI ----------
    private View vConnIndicator;
    private TextView tvConnStatus, tvFreq, tvRssi, tvStereo, tvVolume,
            tvStationCount, tvStatusBar, tvBand;
    private Button btnTuneDown, btnTuneUp, btnScan, btnMute, btnMono;
    private Button btnPowerOff, btnSoftReset;


    private TextView btnSettings, btnConnect;
    private ProgressBar pbSignal;
    private SeekBar sbVolume;
    private ListView lvStations;

    // ---------- BLE ----------
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic sendCharacteristic;
    private String targetDeviceMac;
    private boolean isConnecting = false;
    private boolean isScanningBle = false;

    private LinearLayout tv_freq_Layout;
    private List<BluetoothDevice> bleDeviceList = new ArrayList<>();
    private List<String> bleDeviceNames = new ArrayList<>();
    private ArrayAdapter<String> bleDeviceAdapter;
    private AlertDialog deviceDialog;

    // ---------- 数据 ----------
    private String receiveEncoding = "UTF-8";
    private String sendEncoding = "UTF-8";
    private SharedPreferences sp;
    private List<Station> stationList = new ArrayList<>();
    private StationAdapter stationAdapter;

    // ---------- 状态 ----------
    private int currentFreqX10 = 8960;
    private int currentVolume = 8;
    private boolean isMuted = false;
    private boolean isMono = false;
    private boolean isScanningRadio = false;

    // ---------- 通信缓冲 ----------
    private StringBuilder recvBuffer = new StringBuilder();
    private long lastChunkTime = 0;
    private long lastReceiveTime = 0;

    private Handler mainHandler = new Handler(Looper.getMainLooper());

    // ========== 日志缓冲 ==========
    private StringBuilder logBuffer = new StringBuilder();
    private static final int MAX_LOG_LINES = 500;
    private Runnable scanTimeoutRunnable;
    private static final int SCAN_TIMEOUT_MS = 30000;   // 30 秒超时
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_radio);

        sp = getSharedPreferences(SP_NAME, MODE_PRIVATE);

        initViews();
        initBluetooth();
        initListeners();
        loadSavedStations();
        tryAutoConnect();
    }

    private void initViews() {
        vConnIndicator = findViewById(R.id.v_conn_indicator);
        tvConnStatus   = findViewById(R.id.tv_conn_status);
        tv_freq_Layout = findViewById(R.id.tv_freq_Layout);
        tvFreq         = findViewById(R.id.tv_freq);
        tvRssi         = findViewById(R.id.tv_rssi);
        tvStereo       = findViewById(R.id.tv_stereo);
        tvVolume       = findViewById(R.id.tv_volume);
        tvStationCount = findViewById(R.id.tv_station_count);
        tvStatusBar    = findViewById(R.id.tv_status_bar);
        tvBand         = findViewById(R.id.tv_band);

        btnTuneDown = findViewById(R.id.btn_tune_down);
        btnTuneUp   = findViewById(R.id.btn_tune_up);
        btnScan     = findViewById(R.id.btn_scan);
        btnMute     = findViewById(R.id.btn_mute);
        btnMono     = findViewById(R.id.btn_mono);
        btnPowerOff  = findViewById(R.id.btn_power_off);
        btnSoftReset = findViewById(R.id.btn_soft_reset);
        btnSettings = findViewById(R.id.btn_settings);
        btnConnect  = findViewById(R.id.btn_connect);

        pbSignal = findViewById(R.id.pb_signal);
        sbVolume = findViewById(R.id.sb_volume);
        lvStations = findViewById(R.id.lv_stations);

        stationAdapter = new StationAdapter(this, stationList);
        lvStations.setAdapter(stationAdapter);

        sbVolume.setMax(15);
        sbVolume.setProgress(currentVolume);
        tvVolume.setText(String.valueOf(currentVolume));

        tvFreq.setText(formatFreq(currentFreqX10));
        setConnState(false, "未连接");
    }

    private void initBluetooth() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bm.getAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "设备不支持蓝牙", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
    }

    private void initListeners() {
        // 调谐向下
        btnTuneDown.setOnClickListener(v -> {
            int step = sp.getInt(KEY_FREQ_STEP, 10);
            currentFreqX10 -= step;
            if (currentFreqX10 < 8700) currentFreqX10 = 8700;
            sendCommand("F" + formatFreqCmd(currentFreqX10));
            tvFreq.setText(formatFreq(currentFreqX10));
            setStatusBar("调谐到 " + formatFreq(currentFreqX10) + " MHz");
        });

        // 调谐向上
        btnTuneUp.setOnClickListener(v -> {
            int step = sp.getInt(KEY_FREQ_STEP, 10);
            currentFreqX10 += step;
            if (currentFreqX10 > 10800) currentFreqX10 = 10800;
            sendCommand("F" + formatFreqCmd(currentFreqX10));
            tvFreq.setText(formatFreq(currentFreqX10));
            setStatusBar("调谐到 " + formatFreq(currentFreqX10) + " MHz");
        });

        // 扫描电台
        btnScan.setOnClickListener(v -> {
            if (isScanningRadio) {
                // 已在扫描中，再按一次取消
                isScanningRadio = false;
                if (scanTimeoutRunnable != null) {
                    mainHandler.removeCallbacks(scanTimeoutRunnable);
                    scanTimeoutRunnable = null;
                }
                btnScan.setText("扫描");
                setStatusBar("扫描已取消");
                return;
            }
            if (bluetoothGatt == null || sendCharacteristic == null) {
                setStatusBar("未连接设备");
                Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show();
                return;
            }
            isScanningRadio = true;
            btnScan.setText("停止");

            stationList.clear();
            stationAdapter.notifyDataSetChanged();
            stationAdapter.setSelectedPosition(-1);
            tvStationCount.setText("0 个");
            saveStations();
            setStatusBar("正在扫描电台...");

            sendCommand("A");

            // 30 秒超时保护
            scanTimeoutRunnable = () -> {
                if (isScanningRadio) {
                    isScanningRadio = false;
                    btnScan.setText("扫描");
                    setStatusBar("扫描超时，自动停止");
                }
            };
            mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS);
        });

        // 静音
        btnMute.setOnClickListener(v -> {
            isMuted = !isMuted;
            sendCommand(isMuted ? "M1" : "M0");
            btnMute.setText(isMuted ? "取消静音" : "静音");
            setStatusBar(isMuted ? "已静音" : "已取消静音");
        });

        // 单声道
        btnMono.setOnClickListener(v -> {
            isMono = !isMono;
            sendCommand(isMono ? "O1" : "O0");
            btnMono.setText(isMono ? "立体声" : "单声道");
            tvStereo.setText(isMono ? "单声道" : "立体声" );
            setStatusBar(isMono ? "单声道" : "立体声");

        });

        // RDA 关机
        btnPowerOff.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("RDA 关机")
                    .setMessage("确认关闭 RDA5807M 收音芯片？")
                    .setPositiveButton("确认", (d, w) -> {
                        sendCommand("P");
                        setStatusBar("RDA 已关机");
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        // RDA 软复位 → 复位完成后自动切到列表中信号最强的电台
        btnSoftReset.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("RDA 软复位")
                    .setMessage("确认软复位 RDA5807M 收音芯片？\n复位后将自动切到列表中信号最强的电台。")
                    .setPositiveButton("确认", (d, w) -> {
                        sendCommand("S");
                        setStatusBar("RDA 软复位已发送，等待复位完成...");

                        // 等待软复位完成（约 2 秒），再切最强台
                        mainHandler.postDelayed(() -> {
                            // 列表为空：无法切台，只同步一次状态
                            if (stationList.isEmpty()) {
                                setStatusBar("列表中无电台，复位后未切台");
                                sendCommand("V" + currentVolume);
                                return;
                            }

                            // 找 RSSI 最大的电台
                            int bestIdx = 0;
                            for (int i = 1; i < stationList.size(); i++) {
                                if (stationList.get(i).getRssi() > stationList.get(bestIdx).getRssi()) {
                                    bestIdx = i;
                                }
                            }
                            Station best = stationList.get(bestIdx);

                            // 切台（内部会发 F 命令、更新 UI、取消静音）
                            selectStation(best, bestIdx);

                            // 软复位会把 RDA 音量回默认，延迟重发一次音量
                            mainHandler.postDelayed(() -> sendCommand("V" + currentVolume), 300);

                            setStatusBar("已复位并切到最强电台 " + best.getDisplayFreq()
                                    + "  " + best.getDisplayRssi());
                        }, 2000);
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        // 音量
        sbVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    currentVolume = progress;
                    tvVolume.setText(String.valueOf(progress));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                sendCommand("V" + seekBar.getProgress());
                setStatusBar("音量 " + seekBar.getProgress());
            }
        });

        // 设置
        btnSettings.setOnClickListener(v -> showSettingsDialog());

        // 连接按钮
        btnConnect.setOnClickListener(v -> {
            if (bluetoothGatt != null && sendCharacteristic != null) {
                // 已连接，弹出断开选项
                new AlertDialog.Builder(this)
                        .setTitle("已连接")
                        .setMessage("当前设备：" + targetDeviceMac)
                        .setPositiveButton("断开", (d, w) -> disconnectDevice())
                        .setNegativeButton("取消", null)
                        .show();
            } else {
                // 未连接，扫描并选择
                startBleScanAndShowDialog();
            }
        });

        // 电台列表点击 → 选台
        lvStations.setOnItemClickListener((parent, view, position, id) -> {
            Station s = stationList.get(position);
            selectStation(s, position);
        });

        // 长按状态栏 → 弹出完整日志
        tvStatusBar.setOnLongClickListener(v -> {
            showLogDialog();
            return true;
        });
    }

    // ==================== 日志对话框 ====================
    // ==================== 日志对话框 ====================
    private void showLogDialog() {
        if (logBuffer.length() == 0) {
            Toast.makeText(this, "暂无日志", Toast.LENGTH_SHORT).show();
            return;
        }

        // 用 LinearLayout 包裹，保证 TextView 焦点正确
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(8, 8, 8, 8);

        // 横向滚动的 ScrollView 套在纵向 ScrollView 外面，
        // 这样长行日志也能左右滑动
        android.widget.HorizontalScrollView hScroll = new android.widget.HorizontalScrollView(this);
        hScroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));

        android.widget.ScrollView vScroll = new android.widget.ScrollView(this);
        vScroll.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        final TextView tv = new TextView(this);
        tv.setText(logBuffer.toString());
        tv.setTextSize(11);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setPadding(24, 24, 24, 24);
        tv.setTextIsSelectable(true);
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(true);
        tv.setLongClickable(true);
        tv.setHorizontallyScrolling(true);
        tv.setTextColor(0xFF000000);

        vScroll.addView(tv);
        hScroll.addView(vScroll);
        container.addView(hScroll);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("设备日志")
                .setView(container)
                .setPositiveButton("关闭", null)
                .setNegativeButton("全选", (d, w) -> {
                    // 全选并拷到剪贴板
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(
                            "radio_log", logBuffer.toString()));
                    Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("分享", (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, logBuffer.toString());
                    startActivity(Intent.createChooser(i, "分享日志"));
                })
                .create();

        dialog.setOnShowListener(d -> {
            // 弹窗显示后强制把焦点给 TextView，长按才能选中
            tv.post(() -> {
                tv.setFocusableInTouchMode(true);
                tv.requestFocus();
            });
        });

        dialog.show();
    }
    /** 点击电台列表项，切换到该电台 */
    private void selectStation(Station s, int position) {
        // 若当前静音，先取消静音
        if (isMuted) {
            sendCommand("M0");
            isMuted = false;
            btnMute.setText("静音");
        }
        sendCommand("F" + s.getFreqText());
        currentFreqX10 = s.getFreqX10();
        tvFreq.setText(s.getFreqText());
        stationAdapter.setSelectedPosition(position);
        pbSignal.setProgress(Math.min(s.getRssi(), pbSignal.getMax()));
        tvRssi.setText("RSSI=" + s.getRssi());
        setStatusBar("已切换到 " + s.getDisplayFreq());
    }

    // ==================== 频率格式化 ====================
    private String formatFreq(int freqX10) {
        return String.format(java.util.Locale.US, "%d.%02d", freqX10 / 100, freqX10 % 100);
    }

    private String formatFreqCmd(int freqX10) {
        int high = freqX10 / 100;
        int low = (freqX10 % 100) / 10;
        return high + "." + low;
    }

    // ==================== 连接状态 ====================
    private void setConnState(boolean connected, String text) {
        runOnUiThread(() -> {
            tvConnStatus.setText(text);
            vConnIndicator.setBackgroundResource(
                    connected ? R.drawable.circle_green : R.drawable.circle_red);

            // 已连接：隐藏"连接"按钮；未连接：显示
            btnConnect.setVisibility(connected ? View.GONE : View.VISIBLE);
            tv_freq_Layout.setVisibility(connected ?  View.VISIBLE : View.GONE);
        });
    }

    private void setStatusBar(String s) {
        runOnUiThread(() -> tvStatusBar.setText(s));
    }

    // ==================== 自动连接 ====================
    private void tryAutoConnect() {
        String lastMac = sp.getString(KEY_LAST_MAC, null);
        if (lastMac == null || lastMac.isEmpty()) {
            setStatusBar("首次使用，请点击右上角「连接」选择设备");
            return;
        }
        targetDeviceMac = lastMac;
        setConnState(false, "自动连接中...");
        if (!bluetoothAdapter.isEnabled()) {
            Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(i, REQ_ENABLE_BT);
            return;
        }
        if (!checkPermissions()) {
            requestPermissions();
            return;
        }
        connectToDevice();
    }

    private void connectToDevice() {
        if (isConnecting) return;
        if (targetDeviceMac == null || targetDeviceMac.isEmpty()) {
            setStatusBar("无 MAC 地址");
            return;
        }
        isConnecting = true;
        setStatusBar("正在连接 " + targetDeviceMac + " ...");
        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(targetDeviceMac);
            bluetoothGatt = device.connectGatt(this, false, gattCallback);
        } catch (Exception e) {
            isConnecting = false;
            setStatusBar("连接失败: " + e.getMessage());
        }
    }

    private void disconnectDevice() {
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (Exception ignored) {}
            bluetoothGatt = null;
        }
        sendCharacteristic = null;
        isConnecting = false;
        setConnState(false, "未连接");
        setStatusBar("已断开连接");
    }

    // ==================== BLE 扫描 + 设备选择弹窗 ====================
    private void startBleScanAndShowDialog() {
        if (!checkPermissions()) {
            requestPermissions();
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(i, REQ_ENABLE_BT);
            return;
        }

        bleDeviceList.clear();
        bleDeviceNames.clear();

        bleDeviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, bleDeviceNames);

        deviceDialog = new AlertDialog.Builder(this)
                .setTitle("选择蓝牙设备")
                .setAdapter(bleDeviceAdapter, (d, which) -> {
                    BluetoothDevice dev = bleDeviceList.get(which);
                    targetDeviceMac = dev.getAddress();
                    stopBleScan();
                    setStatusBar("选择设备 " + targetDeviceMac);
                    connectToDevice();
                })
                .setNegativeButton("取消", (d, w) -> stopBleScan())
                .setOnDismissListener(d -> stopBleScan())
                .create();
        deviceDialog.show();

        setStatusBar("正在扫描蓝牙设备...");
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        bluetoothLeScanner.startScan(new ArrayList<ScanFilter>(), settings, bleScanCallback);
        isScanningBle = true;
    }

    private void stopBleScan() {
        if (isScanningBle && bluetoothLeScanner != null) {
            try {
                bluetoothLeScanner.stopScan(bleScanCallback);
            } catch (Exception ignored) {}
            isScanningBle = false;
        }
    }

    private final ScanCallback bleScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice dev = result.getDevice();
            String name = dev.getName();
            String addr = dev.getAddress();
            if (name == null || name.isEmpty()) return;
            // 去重
            for (BluetoothDevice d : bleDeviceList) {
                if (d.getAddress().equals(addr)) return;
            }
            bleDeviceList.add(dev);
            bleDeviceNames.add(name + "\n" + addr);
            runOnUiThread(() -> {
                if (bleDeviceAdapter != null) bleDeviceAdapter.notifyDataSetChanged();
            });
        }
        @Override
        public void onScanFailed(int errorCode) {
            setStatusBar("扫描失败: " + errorCode);
        }
    };

    // ==================== 发送命令 ====================
    // ==================== 发送命令 ====================
    private static final String CMD_PREFIX = "RDA ";   // 业务命令统一前缀

    private void sendCommand(String cmd) {
        if (bluetoothGatt == null || sendCharacteristic == null) {
            setStatusBar("未连接");
            return;
        }
        try {
            byte[] data = (CMD_PREFIX + cmd + "\r\n").getBytes(sendEncoding);
            sendCharacteristic.setValue(data);
            bluetoothGatt.writeCharacteristic(sendCharacteristic);
        } catch (UnsupportedEncodingException e) {
            setStatusBar("编码错误: " + e.getMessage());
        }
    }

    // ==================== GATT 回调 ====================
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                setConnState(false, "发现服务中...");
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnecting = false;
                sendCharacteristic = null;
                setConnState(false, "未连接");
                setStatusBar("设备已断开");
                if (bluetoothGatt != null) {
                    bluetoothGatt.close();
                    bluetoothGatt = null;
                }
            } else {
                isConnecting = false;
                setConnState(false, "连接失败");
                setStatusBar("连接失败 status=" + status);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setStatusBar("服务发现失败");
                isConnecting = false;
                return;
            }
            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service == null) {
                setStatusBar("未找到服务");
                isConnecting = false;
                return;
            }
            BluetoothGattCharacteristic ch = service.getCharacteristic(CHARACTERISTIC_UUID);
            if (ch == null) {
                setStatusBar("未找到特征");
                isConnecting = false;
                return;
            }
            sendCharacteristic = ch;
            gatt.setCharacteristicNotification(ch, true);
            BluetoothGattDescriptor desc = ch.getDescriptor(DESCRIPTOR_UUID);
            if (desc != null) {
                desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(desc);
            }

            isConnecting = false;
            setConnState(true, "已连接");
            setStatusBar("已连接 " + targetDeviceMac);
            sp.edit().putString(KEY_LAST_MAC, targetDeviceMac).apply();

            // 连接成功后同步一次状态
            mainHandler.postDelayed(() -> {
                sendCommand("R");
                sendCommand("V" + currentVolume);
            }, 300);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            if (data == null || data.length == 0) return;

            long now = System.currentTimeMillis();
            if (recvBuffer.length() > 0 && (now - lastChunkTime) > 200) {
                processLine(recvBuffer.toString());
                recvBuffer.setLength(0);
            }
            lastChunkTime = now;

            String chunk;
            try {
                chunk = new String(data, receiveEncoding);
            } catch (UnsupportedEncodingException e) {
                chunk = new String(data, StandardCharsets.UTF_8);
            }
            recvBuffer.append(chunk);

            int idx;
            while ((idx = recvBuffer.indexOf("\n")) >= 0) {
                String line = recvBuffer.substring(0, idx);
                recvBuffer.delete(0, idx + 1);
                if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                processLine(line);
            }
        }
    };

    // ==================== 日志行解析 ====================
    private void processLine(final String line) {
        if (line == null || line.isEmpty()) return;
        lastReceiveTime = System.currentTimeMillis();

        // 累积到日志缓冲（不涉及 UI，可在 BLE 线程做）
        logBuffer.append(line).append("\n");
        int lineCount = 0;
        for (int i = 0; i < logBuffer.length(); i++) {
            if (logBuffer.charAt(i) == '\n') lineCount++;
        }
        if (lineCount > MAX_LOG_LINES) {
            int cut = logBuffer.indexOf("\n");
            if (cut > 0) logBuffer.delete(0, cut + 1);
        }

        // 所有 UI 更新放到主线程
        runOnUiThread(() -> {
            if (line.startsWith("Set frequency:")) {
                String freqStr = line.substring(14).replace("MHz", "").trim();
                tvFreq.setText(freqStr);
            }
            else if (line.startsWith("Set volume:")) {
                try {
                    int vol = Integer.parseInt(line.substring(11).trim());
                    currentVolume = vol;
                    sbVolume.setProgress(vol);
                    tvVolume.setText(String.valueOf(vol));
                } catch (Exception ignored) {}
            }
            else if (line.startsWith("  ") && line.contains("MHz") && line.contains("RSSI=")) {
                Station s = parseFoundLine(line);
                if (s != null && stationAdapter.findPositionByFreq(s.getFreqText()) < 0) {
                    stationList.add(s);
                    stationAdapter.notifyDataSetChanged();
                    tvStationCount.setText(stationList.size() + " 个");
                    saveStations();
                    setStatusBar("找到 " + s.getDisplayFreq() + "  " + s.getDisplayRssi());
                }
            }
            else if (line.startsWith("Tuning to strongest station:")) {
                int colon = line.indexOf(':');
                String rest = line.substring(colon + 1).trim();
                int space = rest.indexOf(' ');
                if (space > 0) {
                    String freqStr = rest.substring(0, space);
                    tvFreq.setText(freqStr);
                    int pos = stationAdapter.findPositionByFreq(freqStr);
                    if (pos >= 0) stationAdapter.setSelectedPosition(pos);
                }
            }
            else if (line.contains("=== Auto Scan Complete ===")) {
                isScanningRadio = false;
                btnScan.setText("扫描");
                if (scanTimeoutRunnable != null) {
                    mainHandler.removeCallbacks(scanTimeoutRunnable);
                    scanTimeoutRunnable = null;
                }
                setStatusBar("扫描完成，共 " + stationList.size() + " 个电台");
            }

            // 从任意行中提取 RSSI
            if (line.contains("RSSI:")) {
                try {
                    int idx = line.indexOf("RSSI:");
                    String rest = line.substring(idx + 5).trim();
                    if (rest.contains(",")) rest = rest.substring(0, rest.indexOf(",")).trim();
                    if (rest.contains(" ")) rest = rest.substring(0, rest.indexOf(" ")).trim();
                    int rssi = Integer.parseInt(rest);
                    pbSignal.setProgress(Math.min(rssi, pbSignal.getMax()));
                    tvRssi.setText("RSSI=" + rssi);
                } catch (Exception ignored) {}
            }
        });
    }

    private Station parseFoundLine(String line) {
        try {
            String rest = line.trim();
            String[] parts = rest.split("\\s+");
            String freqStr = parts[0];
            int freqX10 = Math.round(Float.parseFloat(freqStr) * 100);
            int rssi = 0;
            for (String p : parts) {
                if (p.startsWith("RSSI=")) {
                    rssi = Integer.parseInt(p.substring(5));
                    break;
                }
            }
            return new Station(freqStr, freqX10, rssi);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 电台列表持久化 ====================
    private void saveStations() {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        sp.edit().putString(KEY_STATIONS, gson.toJson(stationList)).apply();
    }

    private void loadSavedStations() {
        String json = sp.getString(KEY_STATIONS, null);
        if (json == null || json.isEmpty()) return;
        try {
            com.google.gson.reflect.TypeToken<List<Station>> token =
                    new com.google.gson.reflect.TypeToken<List<Station>>() {};
            List<Station> list = new com.google.gson.Gson().fromJson(json, token.getType());
            if (list != null && !list.isEmpty()) {
                stationList.clear();
                stationList.addAll(list);
                stationAdapter.notifyDataSetChanged();
                tvStationCount.setText(stationList.size() + " 个");
                setStatusBar("已加载 " + list.size() + " 个电台");
            }
        } catch (Exception ignored) {}
    }

    // ==================== 设置 ====================
    private void showSettingsDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("设置");

        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(48, 32, 48, 32);

        TextView tv = new TextView(this);
        tv.setText("频率步进");
        ll.addView(tv);

        final String[] stepNames = {"50 kHz", "100 kHz", "200 kHz"};
        final int[] stepValues = {5, 10, 20};

        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, stepNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        int curStep = sp.getInt(KEY_FREQ_STEP, 10);
        for (int i = 0; i < stepValues.length; i++) {
            if (stepValues[i] == curStep) { spinner.setSelection(i); break; }
        }
        ll.addView(spinner);

        b.setView(ll);
        b.setPositiveButton("保存", (dialog, which) -> {
            int idx = spinner.getSelectedItemPosition();
            sp.edit().putInt(KEY_FREQ_STEP, stepValues[idx]).apply();
            setStatusBar("步进已设为 " + stepNames[idx]);
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    // ==================== 权限 ====================
    private String[] getRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean checkPermissions() {
        for (String p : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                return false;
        }
        return true;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, getRequiredPermissions(), REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int rc, @NonNull String[] p, @NonNull int[] g) {
        super.onRequestPermissionsResult(rc, p, g);
        if (rc == REQ_PERM && checkPermissions()) {
            tryAutoConnect();
        }
    }

    @Override
    protected void onActivityResult(int rc, int resultCode, Intent data) {
        super.onActivityResult(rc, resultCode, data);
        if (rc == REQ_ENABLE_BT && resultCode == RESULT_OK) {
            if (checkPermissions()) tryAutoConnect();
            else requestPermissions();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopBleScan();
        if (bluetoothGatt != null) {
            try { bluetoothGatt.disconnect(); bluetoothGatt.close(); }
            catch (Exception ignored) {}
            bluetoothGatt = null;
        }
        sendCharacteristic = null;
    }
}