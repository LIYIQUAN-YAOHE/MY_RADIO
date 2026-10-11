package com.lyqsmart.my_radio;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class VoiceAssistant implements
        TextToSpeech.OnInitListener,
        android.speech.RecognitionListener {

    private static final String TAG = "VoiceAssistant";
    private static final int REQUEST_CODE_SPEECH_INPUT = 1000;
    private Context mContext;
    private Activity mActivity;
    private TextToSpeech mTextToSpeech;
    private SpeechRecognizer mSpeechRecognizer;

    private boolean mTTSInitialized = false;
    private boolean mSpeechRecognitionAvailable = false;
    private boolean mIsListening = false;

    private Locale mLocale = Locale.getDefault();
    private float mSpeechRate = 1.0f;
    private float mPitch = 1.0f;

    private VoiceAssistantListener mListener;

    // 语音识别重试机制
    private int mRetryCount = 0;
    private static final int MAX_RETRY_COUNT = 2;

    public interface VoiceAssistantListener {
        void onInitialized(boolean ttsSuccess, boolean speechRecognitionAvailable);
        void onSpeechResult(String result);
        void onSpeechPartialResult(String partialResult);
        void onSpeechError(String errorMessage);
        void onSpeechStart();
        void onSpeechEnd();
        void onTTSStart(String utteranceId);
        void onTTSDone(String utteranceId);
        void onTTSError(String utteranceId, String error);
        void onInteractionComplete(String question, String answer);

        // 新增：引导用户安装应用的接口
        void onNeedInstallVoiceRecognition();
    }

    public VoiceAssistant(Activity activity) {
        this.mActivity = activity;
        this.mContext = activity.getApplicationContext();
        initialize();
    }

    public VoiceAssistant(Activity activity, Locale locale) {
        this.mActivity = activity;
        this.mContext = activity.getApplicationContext();
        this.mLocale = locale;
        initialize();
    }

    private void initialize() {
        initializeTTS();
        initializeSpeechRecognition();
    }

    public void setListener(VoiceAssistantListener listener) {
        this.mListener = listener;
        if (mTextToSpeech != null || mSpeechRecognizer != null) {
            notifyInitializationStatus();
        }
    }

    // ==================== 文字转语音功能 ====================

    private void initializeTTS() {
        mTextToSpeech = new TextToSpeech(mContext, this);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = mTextToSpeech.setLanguage(mLocale);
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS语言不支持: " + mLocale);
                mTTSInitialized = false;
                if (mListener != null) {
                    mListener.onTTSError("init", "语言数据缺失，请安装语音数据");
                }
            } else {
                mTTSInitialized = true;
                mTextToSpeech.setSpeechRate(mSpeechRate);
                mTextToSpeech.setPitch(mPitch);
                setupTTSListener();
                Log.d(TAG, "TTS初始化成功");
            }
        } else {
            mTTSInitialized = false;
            Log.e(TAG, "TTS初始化失败");
            if (mListener != null) {
                mListener.onTTSError("init", "TTS引擎初始化失败");
            }
        }
        notifyInitializationStatus();
    }

    private void setupTTSListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
            mTextToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    Log.d(TAG, "TTS开始: " + utteranceId);
                    if (mListener != null) {
                        mListener.onTTSStart(utteranceId);
                    }
                }

                @Override
                public void onDone(String utteranceId) {
                    Log.d(TAG, "TTS完成: " + utteranceId);
                    if (mListener != null) {
                        mListener.onTTSDone(utteranceId);
                    }
                }

                @Override
                public void onError(String utteranceId, int errorCode) {
                    String error = getTTSErrorText(errorCode);
                    Log.e(TAG, "TTS错误: " + utteranceId + " - " + error);
                    if (mListener != null) {
                        mListener.onTTSError(utteranceId, error);
                    }
                }

                // 兼容旧版本
                @Override
                public void onError(String utteranceId) {
                    onError(utteranceId, TextToSpeech.ERROR);
                }
            });
        }
    }

    public void speak(String text) {
        speak(text, "tts_" + System.currentTimeMillis());
    }

    public void speak(String text, String utteranceId) {
        if (!mTTSInitialized || mTextToSpeech == null) {
            Log.e(TAG, "TTS未初始化");
            if (mListener != null) {
                mListener.onTTSError(utteranceId, "TTS未初始化");
            }
            return;
        }

        if (text == null || text.trim().isEmpty()) {
            Log.e(TAG, "朗读文本为空");
            return;
        }

        try {
            HashMap<String, String> params = new HashMap<>();
            params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);

            int result = mTextToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, params);
            if (result == TextToSpeech.ERROR) {
                Log.e(TAG, "朗读失败");
                if (mListener != null) {
                    mListener.onTTSError(utteranceId, "语音合成失败");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "语音合成异常: " + e.getMessage());
            if (mListener != null) {
                mListener.onTTSError(utteranceId, "异常: " + e.getMessage());
            }
        }
    }

    public void stopSpeaking() {
        if (mTextToSpeech != null && mTTSInitialized) {
            mTextToSpeech.stop();
        }
    }

    // ==================== 语音识别功能 - 优化版本 ====================

    private void initializeSpeechRecognition() {
        // 首先检查 SpeechRecognizer 是否可用
        mSpeechRecognitionAvailable = SpeechRecognizer.isRecognitionAvailable(mContext);

        if (mSpeechRecognitionAvailable) {
            try {
                mSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(mContext);
                mSpeechRecognizer.setRecognitionListener(this);
                Log.d(TAG, "语音识别初始化成功");
            } catch (Exception e) {
                Log.e(TAG, "语音识别初始化失败: " + e.getMessage());
                mSpeechRecognitionAvailable = false;
            }
        } else {
            Log.w(TAG, "设备不支持语音识别");
        }
        notifyInitializationStatus();
    }

    /**
     * 检查是否有应用可以处理语音识别Intent
     */
    private boolean isSpeechRecognitionIntentAvailable() {
        if (mActivity == null) return false;

        try {
            PackageManager pm = mActivity.getPackageManager();
            List activities = pm.queryIntentActivities(
                    new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0);
            boolean hasApp = activities.size() > 0;
            Log.d(TAG, "语音识别应用检查: " + (hasApp ? "有可用应用" : "无可用应用"));
            return hasApp;
        } catch (Exception e) {
            Log.e(TAG, "检查语音识别应用时出错: " + e.getMessage());
            return false;
        }
    }

    /**
     * 使用系统界面进行语音识别（增强版本）
     */
    public void startSpeechRecognition() {
        if (!mSpeechRecognitionAvailable) {
            notifySpeechError("设备不支持语音识别");
            return;
        }

        // 检查是否有应用可以处理语音识别
        if (!isSpeechRecognitionIntentAvailable()) {
            // 如果没有语音识别应用，通知Activity显示安装引导
            if (mListener != null) {
                mListener.onNeedInstallVoiceRecognition();
            }
            // 同时尝试使用后台识别
            Log.d(TAG, "无语音识别应用，尝试使用后台识别");
            startBackgroundSpeechRecognition();
            return;
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, mLocale);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "请开始说话...");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);

        // 添加更多兼容性选项
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
        }

        try {
            mActivity.startActivityForResult(intent, REQUEST_CODE_SPEECH_INPUT);
            notifySpeechStart();
            mRetryCount = 0; // 重置重试计数
            Log.d(TAG, "启动系统语音识别界面成功");
        } catch (Exception e) {
            Log.e(TAG, "启动语音识别失败: " + e.getMessage());

            // 如果系统界面失败，尝试使用后台识别
            if (mRetryCount < MAX_RETRY_COUNT) {
                mRetryCount++;
                Log.d(TAG, "尝试使用后台识别，重试次数: " + mRetryCount);
                startBackgroundSpeechRecognition();
            } else {
                notifySpeechError("语音识别启动失败，请检查系统设置");
            }
        }
    }

    /**
     * 开始后台语音识别（优化版本）
     */
    public void startBackgroundSpeechRecognition() {
        if (!mSpeechRecognitionAvailable || mSpeechRecognizer == null) {
            notifySpeechError("语音识别不可用");
            return;
        }

        // 如果正在监听，先停止
        if (mIsListening) {
            stopSpeechRecognition();
            // 等待一小段时间再开始
            new android.os.Handler().postDelayed(this::doStartBackgroundRecognition, 300);
        } else {
            doStartBackgroundRecognition();
        }
    }

    private void doStartBackgroundRecognition() {
        try {
            if (mSpeechRecognizer == null) {
                notifySpeechError("语音识别器未初始化");
                return;
            }

            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, mLocale);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);

            // 添加超时设置
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000);

            mSpeechRecognizer.startListening(intent);
            mIsListening = true;
            mRetryCount = 0; // 重置重试计数
            Log.d(TAG, "后台语音识别启动成功");

        } catch (Exception e) {
            Log.e(TAG, "后台语音识别启动失败: " + e.getMessage());
            notifySpeechError("识别服务忙，请稍后重试");

            // 重试机制
            if (mRetryCount < MAX_RETRY_COUNT) {
                mRetryCount++;
                Log.d(TAG, "重试后台识别，次数: " + mRetryCount);
                new android.os.Handler().postDelayed(this::doStartBackgroundRecognition, 500);
            }
        }
    }

    /**
     * 停止语音识别
     */
    public void stopSpeechRecognition() {
        if (mSpeechRecognizer != null && mIsListening) {
            try {
                mSpeechRecognizer.stopListening();
                mIsListening = false;
                Log.d(TAG, "语音识别已停止");
            } catch (Exception e) {
                Log.e(TAG, "停止语音识别失败: " + e.getMessage());
            }
        }
    }

    /**
     * 取消语音识别
     */
    public void cancelSpeechRecognition() {
        if (mSpeechRecognizer != null) {
            try {
                mSpeechRecognizer.cancel();
                mIsListening = false;
                Log.d(TAG, "语音识别已取消");
            } catch (Exception e) {
                Log.e(TAG, "取消语音识别失败: " + e.getMessage());
            }
        }
    }

    /**
     * 处理Activity结果
     */
    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_SPEECH_INPUT) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                ArrayList<String> results = data.getStringArrayListExtra(
                        RecognizerIntent.EXTRA_RESULTS
                );
                if (results != null && !results.isEmpty()) {
                    String recognizedText = results.get(0);
                    notifySpeechResult(recognizedText);
                    return true;
                } else {
                    notifySpeechError("没有识别到语音内容");
                }
            } else if (resultCode == Activity.RESULT_CANCELED) {
                notifySpeechError("用户取消了语音识别");
            } else {
                notifySpeechError("语音识别失败");
            }
            return true;
        }
        return false;
    }

    // ==================== 语音识别监听器 ====================

    @Override
    public void onReadyForSpeech(Bundle params) {
        notifySpeechStart();
        mIsListening = true;
        Log.d(TAG, "语音识别准备就绪");
    }

    @Override
    public void onBeginningOfSpeech() {
        Log.d(TAG, "开始检测到语音");
    }

    @Override
    public void onRmsChanged(float rmsdB) {
        // 可用于音量可视化
    }

    @Override
    public void onBufferReceived(byte[] buffer) {}

    @Override
    public void onEndOfSpeech() {
        notifySpeechEnd();
        mIsListening = false;
        Log.d(TAG, "语音输入结束");
    }

    @Override
    public void onError(int error) {
        String errorMessage = getSpeechRecognitionErrorText(error);
        Log.e(TAG, "语音识别错误: " + errorMessage + " (代码: " + error + ")");

        mIsListening = false;

        // 特定错误处理
        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
            // 识别服务忙，尝试恢复
            if (mRetryCount < MAX_RETRY_COUNT) {
                mRetryCount++;
                Log.d(TAG, "识别服务忙，尝试恢复，重试次数: " + mRetryCount);
                new android.os.Handler().postDelayed(this::startBackgroundSpeechRecognition, 1000);
                return;
            }
        }

        notifySpeechError(errorMessage);
    }

    @Override
    public void onResults(Bundle results) {
        ArrayList<String> matches = results.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION
        );
        if (matches != null && !matches.isEmpty()) {
            String result = matches.get(0);
            notifySpeechResult(result);
            Log.d(TAG, "语音识别成功: " + result);
        } else {
            notifySpeechError("没有识别到结果");
        }
        mIsListening = false;
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        ArrayList<String> partialMatches = partialResults.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION
        );
        if (partialMatches != null && !partialMatches.isEmpty() && mListener != null) {
            String partialResult = partialMatches.get(0);
            mListener.onSpeechPartialResult(partialResult);
            Log.d(TAG, "部分识别结果: " + partialResult);
        }
    }

    @Override
    public void onEvent(int eventType, Bundle params) {}

    // ==================== 高级功能 ====================

    /**
     * 问答模式：提问并等待回答
     */
    public void askQuestion(String question) {
        speak(question, "question");
        new android.os.Handler().postDelayed(() -> {
            startBackgroundSpeechRecognition();
        }, 2000);
    }

    /**
     * 交互模式：说话并获取回答
     */
    public void startConversation() {
        speak("请问有什么可以帮您？", "conversation_start");
        new android.os.Handler().postDelayed(() -> {
            startBackgroundSpeechRecognition();
        }, 1500);
    }

    // ==================== 配置方法 ====================

    /**
     * 设置语言
     */
    public void setLocale(Locale locale) {
        this.mLocale = locale;
        if (mTTSInitialized && mTextToSpeech != null) {
            mTextToSpeech.setLanguage(locale);
        }
    }

    /**
     * 设置语速
     */
    public void setSpeechRate(float rate) {
        this.mSpeechRate = Math.max(0.5f, Math.min(2.0f, rate));
        if (mTTSInitialized && mTextToSpeech != null) {
            mTextToSpeech.setSpeechRate(mSpeechRate);
        }
    }

    /**
     * 设置音调
     */
    public void setPitch(float pitch) {
        this.mPitch = Math.max(0.5f, Math.min(2.0f, pitch));
        if (mTTSInitialized && mTextToSpeech != null) {
            mTextToSpeech.setPitch(mPitch);
        }
    }

    // ==================== 状态检查 ====================

    public boolean isTTSInitialized() {
        return mTTSInitialized;
    }

    public boolean isSpeechRecognitionAvailable() {
        return mSpeechRecognitionAvailable;
    }

    public boolean isSpeaking() {
        return mTextToSpeech != null && mTextToSpeech.isSpeaking();
    }

    public boolean isListening() {
        return mIsListening;
    }

    // ==================== 资源释放 ====================

    public void destroy() {
        // 停止所有语音活动
        stopSpeaking();
        cancelSpeechRecognition();

        if (mTextToSpeech != null) {
            mTextToSpeech.shutdown();
            mTextToSpeech = null;
        }

        if (mSpeechRecognizer != null) {
            mSpeechRecognizer.destroy();
            mSpeechRecognizer = null;
        }

        mIsListening = false;
        mListener = null;
        mContext = null;
        mActivity = null;
    }

    // ==================== 私有辅助方法 ====================

    private void notifyInitializationStatus() {
        if (mListener != null) {
            mListener.onInitialized(mTTSInitialized, mSpeechRecognitionAvailable);
        }
    }

    private void notifySpeechStart() {
        if (mListener != null) {
            mListener.onSpeechStart();
        }
    }

    private void notifySpeechEnd() {
        if (mListener != null) {
            mListener.onSpeechEnd();
        }
    }

    private void notifySpeechResult(String result) {
        if (mListener != null) {
            mListener.onSpeechResult(result);
        }
    }

    private void notifySpeechError(String error) {
        if (mListener != null) {
            mListener.onSpeechError(error);
        }
    }

    private String getTTSErrorText(int errorCode) {
        switch (errorCode) {
            case TextToSpeech.ERROR_NETWORK: return "网络错误";
            case TextToSpeech.ERROR_NETWORK_TIMEOUT: return "网络超时";
            case TextToSpeech.ERROR_NOT_INSTALLED_YET: return "语音数据未安装";
            case TextToSpeech.ERROR_SERVICE: return "服务错误";
            case TextToSpeech.ERROR_SYNTHESIS: return "合成错误";
            default: return "未知错误: " + errorCode;
        }
    }

    private String getSpeechRecognitionErrorText(int errorCode) {
        switch (errorCode) {
            case SpeechRecognizer.ERROR_AUDIO: return "音频错误";
            case SpeechRecognizer.ERROR_CLIENT: return "客户端错误";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "权限不足";
            case SpeechRecognizer.ERROR_NETWORK: return "网络错误";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "网络超时";
            case SpeechRecognizer.ERROR_NO_MATCH: return "没有匹配结果";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "识别服务忙";
            case SpeechRecognizer.ERROR_SERVER: return "服务器错误";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "没有说话检测到";
            default: return "未知错误: " + errorCode;
        }
    }
}