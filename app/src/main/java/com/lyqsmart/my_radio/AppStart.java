package com.lyqsmart.my_radio;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.util.Locale;

public class AppStart extends Activity {
    private VoiceAssistant voiceAssistant;
    private WebView webView;
    private Handler handler = new Handler();
    private boolean hasJumped = false; // 防止多次跳转

    // 配置常量
    private static final String HTML_FILE = "file:///android_asset/html/account_number_star.html";
    private static final int WELCOME_DELAY = 500; // 1秒后播放欢迎语音
    private static final int JUMP_DELAY = 100; // 跳转前延迟500毫秒
    private static final String JS_INTERFACE_NAME = "AndroidInterface";

    // JavaScript接口类
    public class AndroidInterface {
        @JavascriptInterface
        public void onUploadComplete() {
            runOnUiThread(() -> {
                if (!hasJumped) {
                    hasJumped = true;
                    jumpToNextActivity();
                }
            });
        }

        @JavascriptInterface
        public void showToast(String message) {
            runOnUiThread(() -> {
                Toast.makeText(AppStart.this, message, Toast.LENGTH_SHORT).show();
            });
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.app_start);

        initVoiceAssistant();
        initWebView();
        scheduleWelcomeMessage();

    }

    private void initVoiceAssistant() {
        voiceAssistant = new VoiceAssistant(this, Locale.CHINESE);
    }

    private void initWebView() {
        webView = findViewById(R.id.webview_container);
        setupWebSettings();
        setupWebViewClient();
        addJavaScriptInterface();
        loadHtmlContent();
    }

    private void setupWebSettings() {
        WebSettings webSettings = webView.getSettings();

        // 启用JavaScript
        webSettings.setJavaScriptEnabled(true);

        // 启用DOM存储
        webSettings.setDomStorageEnabled(true);

        // 允许文件访问
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true);

        // 视图设置
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);

        // 缩放控制
        webSettings.setBuiltInZoomControls(false);
        webSettings.setDisplayZoomControls(false);

        // 缓存设置 - 兼容不同Android版本
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // 对于旧版本Android，设置应用缓存
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            try {
                // 使用反射调用旧版本的API
                webSettings.getClass().getMethod("setAppCacheEnabled", boolean.class)
                        .invoke(webSettings, true);
                webSettings.getClass().getMethod("setAppCachePath", String.class)
                        .invoke(webSettings, getCacheDir().getAbsolutePath());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // 编码设置
        webSettings.setDefaultTextEncodingName("UTF-8");

        // 其他设置
        webSettings.setSupportZoom(false);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(false);
        webSettings.setLoadsImagesAutomatically(true);
        webSettings.setRenderPriority(WebSettings.RenderPriority.HIGH);

        // 支持混合内容
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
    }

    private void setupWebViewClient() {
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 注入版本信息
                String version = VersionUtils.getVersionName(AppStart.this);
                view.evaluateJavascript(
                        "document.getElementById('versionInfo').innerText = '" + version + "';",
                        null
                );
                // 页面加载完成后的处理
                onHtmlLoaded();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // 在WebView中打开所有链接
                return false;
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                // 加载错误时跳转到下一个页面
                jumpToNextActivityWithError();
            }
        });
    }

    private void addJavaScriptInterface() {
        webView.addJavascriptInterface(new AndroidInterface(), JS_INTERFACE_NAME);
    }

    private void loadHtmlContent() {
        try {
            webView.loadUrl(HTML_FILE);
        } catch (Exception e) {
            e.printStackTrace();
            // 如果加载HTML失败，直接跳转到下一个页面
            jumpToNextActivityWithError();
        }
    }

    private void onHtmlLoaded() {
        // HTML加载完成后的处理
        // 可以在这里注入额外的JavaScript代码
        injectAdditionalJavaScript();
    }

    private void injectAdditionalJavaScript() {
        // 如果需要，可以在这里注入额外的JavaScript代码
        // 例如：webView.evaluateJavascript("javascript:...", null);
    }

    private void scheduleWelcomeMessage() {
        new Handler().postDelayed(() -> {
            if (voiceAssistant != null) {
                voiceAssistant.speak("欢迎您的使用");
            }
        }, WELCOME_DELAY);
    }

    private void jumpToNextActivity() {
        runOnUiThread(() -> {
            // 显示跳转提示
//            Toast.makeText(this, "上传完成，正在跳转...", Toast.LENGTH_SHORT).show();

            // 延迟后跳转
            new Handler().postDelayed(() -> {
                Intent intent = new Intent(AppStart.this, RadioActivity.class);
                startActivity(intent);
                finish(); // 结束当前activity
            }, JUMP_DELAY);
        });
    }

    private void jumpToNextActivityWithError() {
        runOnUiThread(() -> {
            Toast.makeText(this, "加载动画失败，正在跳转...", Toast.LENGTH_SHORT).show();
            jumpToNextActivity();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 暂停WebView
        if (webView != null) {
            webView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 恢复WebView
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (webView != null) {
            // 从父View中移除WebView
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.setWebViewClient(null);
            webView.setWebChromeClient(null);
            webView.destroy();
            webView = null;
        }
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
    }


    @Override
    public void onBackPressed() {
        // 禁用返回键，防止用户中断动画
        // 如果需要启用返回键，可以添加确认对话框
        // super.onBackPressed();
    }
}