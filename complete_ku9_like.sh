#!/bin/bash
# complete_ku9_like.sh - 对 witv 项目进行深度改造，植入酷9风格功能骨架

set -e

echo "🔥 开始深度改造 witv → 酷9风格..."

# -------- 1. 更新依赖 (同前) --------
APP_BUILD_GRADLE="app/build.gradle"
if [ ! -f "$APP_BUILD_GRADLE" ]; then
    echo "❌ 找不到 $APP_BUILD_GRADLE"
    exit 1
fi
cp "$APP_BUILD_GRADLE" "$APP_BUILD_GRADLE.bak"
sed -i '/dependencies {/a \    // 酷9增强依赖\n    implementation "com.squareup.okhttp3:okhttp:4.12.0"\n    implementation "com.google.code.gson:gson:2.10.1"\n    implementation "org.mozilla:rhino:1.7.14"\n    implementation "com.github.bumptech.glide:glide:4.16.0"\n    implementation "androidx.preference:preference:1.2.1"' "$APP_BUILD_GRADLE"

# -------- 2. 在现有 Activity 中添加解码/比例设置菜单 --------
# 假设主播放 Activity 是 com.whyun.witv.MainActivity（请根据实际调整）
MAIN_ACTIVITY="app/src/main/java/com/whyun/witv/MainActivity.java"
if [ -f "$MAIN_ACTIVITY" ]; then
    # 在 onCreate 末尾插入初始化设置
    sed -i '/super.onCreate/ a \        // 酷9设置初始化\n        PlayerConfigManager configManager = new PlayerConfigManager(this);\n        // 应用保存的解码和比例设置\n        configManager.applySettings();' "$MAIN_ACTIVITY"

    # 在 onOptionsItemSelected 中增加菜单项处理（需先确保有菜单）
    # 这里插入一个简单的菜单处理示例（实际需调整）
    cat >> "$MAIN_ACTIVITY" <<EOF

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_decoder_hw) {
            PlayerConfigManager.setDecoder(PlayerConfigManager.DECODER_HARDWARE);
            return true;
        } else if (id == R.id.action_decoder_sw) {
            PlayerConfigManager.setDecoder(PlayerConfigManager.DECODER_SOFTWARE);
            return true;
        } else if (id == R.id.action_aspect_ratio) {
            // 显示比例选择对话框
            showAspectRatioDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
EOF
else
    echo "⚠️ 未找到 MainActivity，跳过菜单修改"
fi

# -------- 3. 修改源管理器，支持 TXT 格式 --------
# 假设源管理类为 com.whyun.witv.data.SourceLoader
SOURCE_LOADER="app/src/main/java/com/whyun/witv/data/SourceLoader.java"
if [ -f "$SOURCE_LOADER" ]; then
    # 在类中添加解析 TXT 的方法
    sed -i '/public.*loadSources/ a \    // 酷9: 支持 TXT 格式\n    private void parseTxtSource(String url) { /* TODO: 实现 */ }' "$SOURCE_LOADER"
    # 在加载逻辑中增加判断 .txt 后缀
    sed -i '/if.*\.m3u/ a \        else if (url.endsWith(".txt")) { parseTxtSource(url); }' "$SOURCE_LOADER"
else
    echo "⚠️ 未找到 SourceLoader，TXT支持需手动实现"
fi

# -------- 4. 创建 EPG 多格式解析器（示例类） --------
mkdir -p app/src/main/java/com/whyun/witv/epg
cat > app/src/main/java/com/whyun/witv/epg/EPGParserFactory.java <<EOF
package com.whyun.witv.epg;

public class EPGParserFactory {
    public static final int FORMAT_DIYP = 1;
    public static final int FORMAT_BAICHUAN = 2;
    public static final int FORMAT_SUPERTV = 3;
    public static final int FORMAT_XMLTV = 4;

    public static EPGParser getParser(int format) {
        switch (format) {
            case FORMAT_DIYP: return new DIYPParser();
            case FORMAT_BAICHUAN: return new BaichuanParser();
            case FORMAT_SUPERTV: return new SuperTVParser();
            case FORMAT_XMLTV: return new XMLTVParser();
            default: return null;
        }
    }
}

interface EPGParser {
    void parse(String data);
}
// 后续需实现具体解析类
EOF

# -------- 5. 修改频道列表布局，增加收藏图标 --------
# 假设频道列表项布局为 item_channel.xml
ITEM_LAYOUT="app/src/main/res/layout/item_channel.xml"
if [ -f "$ITEM_LAYOUT" ]; then
    # 在布局末尾添加一个 ImageView 用作收藏按钮
    sed -i '/<\/LinearLayout>/ i \    <ImageView\n        android:id="@+id/iv_favorite"\n        android:layout_width="24dp"\n        android:layout_height="24dp"\n        android:src="@drawable/ic_favorite_border"\n        android:layout_gravity="center_vertical"\n        android:padding="4dp" />' "$ITEM_LAYOUT"
else
    echo "⚠️ 未找到 item_channel.xml，请手动添加收藏图标"
fi

# -------- 6. 添加权限（U盘读取） --------
MANIFEST="app/src/main/AndroidManifest.xml"
if [ -f "$MANIFEST" ]; then
    # 在 <manifest> 下添加权限
    sed -i '/<manifest / a \    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />\n    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />\n    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />' "$MANIFEST"
fi

# -------- 7. 创建 PlayerConfigManager（真正的实现） --------
mkdir -p app/src/main/java/com/whyun/witv/player
cat > app/src/main/java/com/whyun/witv/player/PlayerConfigManager.java <<EOF
package com.whyun.witv.player;

import android.content.Context;
import android.content.SharedPreferences;

public class PlayerConfigManager {
    private static final String PREF_NAME = "ku9_config";
    public static final int DECODER_HARDWARE = 0;
    public static final int DECODER_SOFTWARE = 1;
    private static SharedPreferences prefs;

    public PlayerConfigManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static int getDecoder() {
        return prefs.getInt("decoder", DECODER_HARDWARE);
    }

    public static void setDecoder(int decoder) {
        prefs.edit().putInt("decoder", decoder).apply();
        // 实际应用需重启播放器
    }

    public static String getAspectRatio() {
        return prefs.getString("aspect_ratio", "16:9");
    }

    public static void setAspectRatio(String ratio) {
        prefs.edit().putString("aspect_ratio", ratio).apply();
    }

    public void applySettings() {
        // 这里获取当前播放器实例并应用设置（需根据项目具体实现）
    }
}
EOF

echo "🎉 改造脚本执行完毕！"
echo ""
echo "📌 接下来你需要："
echo "1. 检查修改过的文件，确保语法正确（尤其注意 sed 插入位置是否正确）。"
echo "2. 实现所有 TODO 和空方法体，特别是："
echo "   - parseTxtSource() 的解析逻辑"
echo "   - EPG 各格式解析器"
echo "   - PlayerConfigManager 与 ExoPlayer 的集成"
echo "   - 收藏功能的数据库/持久化"
echo "   - 频道搜索过滤"
echo "   - U盘文件选择界面"
echo "   - JS 脚本引擎的调用"
echo "3. 在菜单资源文件（res/menu/）中添加对应的菜单项 ID（action_decoder_hw 等）。"
echo "4. 测试编译运行，逐步调试。"
echo ""
echo "🔧 构建 APK： ./gradlew assembleDebug"
