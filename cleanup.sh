#!/bin/bash

# 清理問題 JAR 文件
echo "開始清理 Gradle 緩存..."

# 刪除特定問題的 JAR 文件
PROBLEM_JAR="$HOME/.gradle/caches/jars-9/21f503c55fdde83d51a4f784631b641e/jackson-core-2.17.2.jar"
if [ -f "$PROBLEM_JAR" ]; then
    rm -f "$PROBLEM_JAR"
    echo "已刪除問題 JAR 文件: $PROBLEM_JAR"
fi

# 刪除 modules-2 和 jars-9 目錄
MODULES_DIR="$HOME/.gradle/caches/modules-2"
if [ -d "$MODULES_DIR" ]; then
    rm -rf "$MODULES_DIR"
    echo "已刪除 modules-2 緩存目錄"
fi

JARS_DIR="$HOME/.gradle/caches/jars-9"
if [ -d "$JARS_DIR" ]; then
    rm -rf "$JARS_DIR"
    echo "已刪除 jars-9 緩存目錄"
fi

# 創建臨時目錄
TEMP_DIR="$HOME/temp"
mkdir -p "$TEMP_DIR"
echo "已確保臨時目錄存在: $TEMP_DIR"

# 創建自定義緩存目錄
CUSTOM_GRADLE_HOME="$HOME/cathaybk-gradle-temp"
mkdir -p "$CUSTOM_GRADLE_HOME"
echo "已創建自定義 Gradle 目錄: $CUSTOM_GRADLE_HOME"

echo "緩存清理完成"
echo "請使用以下命令運行 Gradle:"
echo "GRADLE_USER_HOME=\"$CUSTOM_GRADLE_HOME\" ./gradlew build -x buildSearchableOptions --refresh-dependencies --stacktrace" 