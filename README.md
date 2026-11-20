# Muv Patcher（Android）

在手机上把 LemonLoader 注入原版 Unity IL2CPP APK，生成可安装的 v2 签名包；并通过 Shizuku 把上游翻译增量写入游戏目录。

## 功能

- **注入**：选择原版 APK → 替换 `libmain.so`、写入 `assets/LemonLoader/**` → 重写 ZIP → 自签 v2 → 安装/导出
- **纯 Kotlin 实现**：二进制 AXML 解析、raw-copy ZIP 重写与 4KB/16KB 对齐、APK Signature Scheme v2
- **翻译更新**：读取上游 `manifest` 与本地清单做哈希比对，只下载差异；6 路并发 + 连接池 + 多源回退
- **Shizuku**：以 UserService（shell 身份）写入 `Android/data/<包名>/files/MelonLoader/UserData/MuvluvMod/local-translation`

## 环境

- JDK 17
- Android SDK Platform 34、Build Tools 34
- `minSdk 24` / `targetSdk 34`

## 构建

仓库**不包含** 64 MB 的注入物料 `payload.zip`（其中含第三方组件，不便再分发）。构建前自行放入：

```bash
cp /path/to/mlgg-1.0.72.zip app/src/main/assets/payload.zip
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

`local.properties` 需指向本机 SDK，或设置 `ANDROID_HOME`。

## 目录

```
core/        纯 Kotlin 核心（可在 JVM 上单测）：AXML、ZIP、v2 签名、流水线
corecheck/   桌面端命令行自检工具
app/         Android UI、Shizuku UserService、翻译更新
```

## 使用

1. 安装 App，选择原版 APK（**先备份**，注入包与官方包签名不同，安装前需卸载官方版）
2. 开始注入 → 安装结果
3. 首次启动游戏，等待 MelonLoader 解包完成
4. 翻译更新：启动 Shizuku → 在 App 内授权 → 检查更新 → 下载并应用 → 重启游戏

## 说明

- 注入包与原版**同包名、不同签名**，不能共存；需要共存请使用系统分身/工作资料。
- 物料中的 LemonLoader、MuvluvMod 与翻译文本版权归各自作者所有，本仓库不包含这些文件。
- 本项目不包含游戏本体，仅供对已购买游戏的个人使用。
- 签名密钥在首次运行时于设备本地生成，卸载 App 后丢失；丢失后旧包无法覆盖升级。
