package com.lyqsmart.my_radio;


import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

/**
 * 版本信息工具类
 * 用于获取应用的版本信息
 */
public class VersionUtils {

    /**
     * 获取应用版本名称
     *
     * @param context 上下文
     * @return 版本名称（例如："扫描增强版 v1.2.3"）
     */
    public static String getVersionName(Context context) {
        try {
            PackageManager packageManager = context.getPackageManager();
            PackageInfo packageInfo = packageManager.getPackageInfo(
                    context.getPackageName(), 0
            );
            return packageInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return "未知版本";
        }
    }

    /**
     * 获取应用版本代码
     *
     * @param context 上下文
     * @return 版本代码（整数）
     */
    public static int getVersionCode(Context context) {
        try {
            PackageManager packageManager = context.getPackageManager();
            PackageInfo packageInfo = packageManager.getPackageInfo(
                    context.getPackageName(), 0
            );
            return packageInfo.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * 解析版本号的各个部分
     *
     * @param versionName 完整版本名称
     * @return 版本号对象
     */
    public static VersionInfo parseVersion(String versionName) {
        VersionInfo versionInfo = new VersionInfo();

        if (versionName == null || versionName.isEmpty()) {
            return versionInfo;
        }

        try {
            // 移除前缀"扫描增强版 v"
            String versionStr = versionName.replace("扫描增强版 v", "");

            // 按点号分割版本号
            String[] parts = versionStr.split("\\.");

            if (parts.length >= 1) {
                versionInfo.major = Integer.parseInt(parts[0]);
            }
            if (parts.length >= 2) {
                versionInfo.minor = Integer.parseInt(parts[1]);
            }
            if (parts.length >= 3) {
                versionInfo.patch = Integer.parseInt(parts[2]);
            }

            // 解析构建类型
            if (versionName.contains("-DEBUG")) {
                versionInfo.buildType = "调试版";
            } else if (versionName.contains("-BETA")) {
                versionInfo.buildType = "测试版";
            } else if (versionName.contains("-RELEASE") ||
                    (!versionName.contains("-DEBUG") && !versionName.contains("-BETA"))) {
                versionInfo.buildType = "正式版";
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return versionInfo;
    }

    /**
     * 获取格式化后的版本信息
     *
     * @param context 上下文
     * @return 格式化的版本信息字符串
     */
    public static String getFormattedVersionInfo(Context context) {
        String versionName = getVersionName(context);
        int versionCode = getVersionCode(context);

        return String.format("应用版本: %s\n版本代码: %d", versionName, versionCode);
    }

    /**
     * 获取简洁版本信息
     *
     * @param context 上下文
     * @return 只包含版本号的字符串
     */
    public static String getShortVersion(Context context) {
        String versionName = getVersionName(context);
        try {
            // 只提取数字版本号部分
            String versionStr = versionName.replace("扫描增强版 v", "");
            String[] parts = versionStr.split("\\.");
            if (parts.length >= 3) {
                return String.format("v%s.%s.%s", parts[0], parts[1], parts[2]);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return versionName;
    }

    /**
     * 版本信息数据类
     */
    public static class VersionInfo {
        public int major = 0;
        public int minor = 0;
        public int patch = 0;
        public String buildType = "正式版";

        @Override
        public String toString() {
            return String.format("%d.%d.%d (%s)", major, minor, patch, buildType);
        }

        /**
         * 获取数字版本号
         *
         * @return 版本号字符串
         */
        public String getVersionNumber() {
            return String.format("%d.%d.%d", major, minor, patch);
        }

        /**
         * 判断是否为新版本
         *
         * @param other 另一个版本信息
         * @return 当前版本是否比other新
         */
        public boolean isNewerThan(VersionInfo other) {
            if (this.major > other.major) return true;
            if (this.major < other.major) return false;

            if (this.minor > other.minor) return true;
            if (this.minor < other.minor) return false;

            return this.patch > other.patch;
        }
    }
}
