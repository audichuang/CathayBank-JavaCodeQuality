# CathayBk Coding Assistant

國泰銀行程式碼規範輔助插件，幫助開發人員遵循公司的編碼標準和規範。

## 主要功能

1. **API MsgID 檢查**

   - 檢查 Controller 中的 API 方法是否添加了 MsgID 註解
   - 提供快速修復功能自動添加 MsgID 註解

2. **Service 關聯檢查**

   - 檢查 Service 類是否有關聯的 Controller API MsgID
   - 自動提示可以添加從 Controller 來的 MsgID

3. **API 結構生成**
   - 根據 Controller 中的 API 方法生成對應的 Service 介面和實現類
   - 自動添加 MsgID 關聯

## 安裝方法

1. 在 IntelliJ IDEA 中，打開 `Settings` -> `Plugins` -> `Marketplace`
2. 搜索 "CathayBk Coding Assistant"
3. 點擊 "Install" 並重啟 IDE

或者你可以從 releases 頁面下載最新的插件 ZIP 檔案，然後通過 `Settings` -> `Plugins` -> `⚙️` -> `Install Plugin from Disk...` 安裝。

## 使用方法

### API MsgID 檢查

插件會自動檢查 Controller 中的 API 方法是否添加了 MsgID 註解，如果沒有添加，會顯示警告，並提供快速修復選項。

### Service 關聯檢查

當你在 Controller 中使用 Service 時，插件會檢查 Service 類是否有關聯的 MsgID 註解，如果沒有，會提示你添加。

### API 結構生成

1. 在 Controller 的 API 方法內，右鍵點擊
2. 在彈出菜單中選擇 `Generate` -> `生成API結構`
3. 如果方法沒有 MsgID，會提示輸入
4. 插件會自動生成對應的 Service 介面和實現類，並關聯 MsgID

## 開發環境配置

本專案使用 Gradle 構建，可以直接在 IntelliJ IDEA 中打開。

### 開發要求

- IntelliJ IDEA 2023.1 或更高版本
- JDK 11 或更高版本

### 構建步驟

```bash
# 構建專案
./gradlew build

# 運行IDE測試
./gradlew runIde

# 構建插件分發包
./gradlew buildPlugin
```

## 版本歷史

- 1.0.0: 初始版本，提供 API 註解檢查和 Service 關聯功能

## 貢獻指南

歡迎提交問題報告和改進建議。如果你想貢獻代碼，請遵循以下步驟：

1. Fork 本專案
2. 創建你的特性分支 (`git checkout -b feature/amazing-feature`)
3. 提交你的更改 (`git commit -m 'Add some amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 打開 Pull Request

## 授權協議

此專案僅供國泰銀行內部使用，未經許可不得外傳。
