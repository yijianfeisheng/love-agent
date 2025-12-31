# love-agent

离线助老传呼 APP（面向老人场景的拨号辅助）：提供 110/120/119 快捷拨号、离线语音拨号（Vosk）、联系人管理与 SOS 连续呼叫等功能。语音匹配支持普通话与山东口音的常见发音偏差，并尽量降低同音误拨风险。
功能按钮采用更简单的图形化，确保能做到每个功能通俗易懂

## 功能

- 快捷拨号：110 / 120 / 119
- 离线语音拨号：Vosk 离线识别 + 联系人姓名/关键词匹配
- 方言/口音容错：拼音模糊化（平翘舌、前后鼻音）+ 多音字候选拼音
- 联系人来源：App 内联系人 + 系统通讯录（授权 READ_CONTACTS 后自动合并）
- SOS：触发后按顺序轮询呼叫紧急联系人（可取消）

## 目录结构

- `app/`：Android 主应用（Kotlin）
- `server/`：Ktor 示例服务（可选）
- `Front/`：Web Demo（可选，Vue 2）

## 环境要求

- Android Studio（建议）
- JDK：项目 `gradle.properties` 指定了 `org.gradle.java.home`（可按需改为本机 JDK）
- Android：`compileSdk 34`，`minSdk 23`（见 [app/build.gradle](app/build.gradle)）

## 权限说明

见 [AndroidManifest.xml](app/src/main/AndroidManifest.xml)：

- `RECORD_AUDIO`：离线语音识别
- `READ_CONTACTS`：读取系统通讯录用于语音匹配
- `CALL_PHONE`：直接拨号（未授权时会退化到拨号盘）
- `READ_PHONE_STATE` / `READ_CALL_LOG` / `ANSWER_PHONE_CALLS`：SOS 呼叫状态管理与挂断（不同系统版本兼容）
- `INTERNET`：首次启动下载离线语音模型（可选）

## 构建与运行（Android）

### Android Studio

1. 用 Android Studio 打开项目根目录
2. 等待 Gradle Sync 完成
3. 选择 `app` 运行

### 命令行

```bash
./gradlew :app:assembleDebug
```

生成的 APK 位于：

```text
app/build/outputs/apk/debug/
```

## 离线语音模型（Vosk）

首次启动会自动检查/安装模型，优先级：

1. `assets/` 内置的 `vosk-model-small-cn-0.22.zip`（若存在）
2. 外部存储 `Android/data/<包名>/files/model.zip`（手动放入可离线安装）
3. 在线下载并解压：
   - `https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip`

安装完成后会自动加载模型并可开始语音识别。

## 语音拨号如何匹配联系人

实现位置：[MainActivity.kt](app/src/main/java/com/zhulao/elder/MainActivity.kt)

- 先做“口语清洗”，删除“打给/打电话/那个/啊/呢…”等噪声词
- 先走“中文直匹配”（命中姓名或关键词则直接拨号，且优先更长命中）
- 再走“拼音匹配”（pinyin4j，多音字生成多组候选拼音）：
  - 包含匹配优先
  - 否则做编辑距离模糊匹配
  - 若最优候选与第二候选差距不够大，会提示“没听清，请再说一遍”，避免同音误拨

方言/口音容错（山东口音常见）：

- 平翘舌不分：`zh/ch/sh` → `z/c/s`
- 前后鼻音不分：`ng` → `n`

## 联系人管理

联系人数据存储在应用 SharedPreferences（App 内联系人），并在授权后合并系统通讯录联系人。

- 合并逻辑：[ContactManager.getAllContacts](app/src/main/java/com/zhulao/elder/ContactManager.kt)
- App 内联系人可设置“呼叫词/关键词”，用于语音命中

## SOS

SOS 相关逻辑在 [SOSActivity.kt](app/src/main/java/com/zhulao/elder/SOSActivity.kt)。

## 可选模块

### server（Ktor）

```bash
./gradlew :server:run
```

默认监听 `0.0.0.0:8080`：

- `GET /api/health`
- `GET /api/keywords`

### Front（Vue 2 Demo）

```bash
cd Front/demo
npm install
npm run dev
```

## Lint / 构建检查

```bash
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

## 运行界面图片
```
![_cgi-bin_mmwebwx-bin_webwxgetmsgimg?? MsgID=4838249319436061128 skey=@crypt_4cf37048_800e1bc767ecc82043033d18d8f0eb33 mmweb_appid=wx_webfilehelper](https://github.com/user-attachments/assets/f7d11f67-e6e1-4b9f-b7a5-228f246086f2)
```
