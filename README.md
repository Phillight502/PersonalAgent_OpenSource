# PersonalAgent

本地优先的 AI 私人助理 Android 应用：用自然语言对话管理日程、待办、计划与心愿，基于智谱 GLM Function Calling 实现多意图识别，全部数据仅存本机。

A local-first AI personal assistant for Android: manage schedules, todos, plans and wishes through natural-language chat powered by Zhipu GLM Function Calling. All data stays on the device.

[中文](#中文) ｜ [English](#english)

---

# 中文

## 项目简介

PersonalAgent 是一款「本地 AI 记忆型私人助理」。你可以用一句话告诉它多件事（"明天下午三点提醒我开会，顺便帮我记一下小王的电话"），它会通过大模型 Function Calling 自动拆解意图、创建事项、存取长期记忆，并在到点时用系统级闹钟准时提醒。

- 当前开源版本：**v1.2**（versionCode 3），对应首个对外正式发布版
- 最低系统：Android 7.0（minSdk 24）；目标/编译 SDK：36
- 应用包名：`com.personalagent.app`

## 功能特性

- **AI 对话**：SSE 流式逐字输出、多意图并发、拍照/相册图片识别、TTS 语音播报、失败重试、停止生成
- **Function Calling 工具链**：事项的增删改查、完成/取消、长期记忆存取等 12 个工具；新建与修改需用户在确认卡片上逐条勾选执行，删除永远强制二次确认
- **四类事项**：日程 / 待办 / 计划 / 心愿，支持结束时间、参与人物、工作/私人范围、优先级、重复规则、归档恢复
- **定时提醒**：基于 `AlarmManager.setAlarmClock` 的系统级精确闹钟，通知内可「标记完成 / 延后 10 分钟」；重复事项自动顺延；开机与系统时间变更后自动重调度；国产 ROM 后台保活引导
- **日程常驻通知**：通知栏实时展示进行中事项、下一项安排与倒计时
- **时间智能**：自然语言时间归一化（"下周五下午三点半"）、空闲时段查询与 AI 代排、日程冲突检测与合理性评审
- **长期记忆**：个人画像、人脉、纪念日、自由笔记四类，对话中自动存取
- **隐私合规**：首次启动协议确认门、应用内离线《隐私政策》《用户协议》、一键撤回授权并清空全部本地数据
- **数据自主**：Room 全量本地存储，支持 JSON 备份导入导出；API Key 仅保存在本机

## 边界与免责声明（请务必阅读）

本节是本项目最重要的部分。使用、复制或二次开发本仓库代码，即视为你已理解并接受以下全部条款。

1. **源码协议**：本项目全部源码以 **MIT 协议**开源（见 [LICENSE](LICENSE)），允许任何人自由使用、复制、修改、合并、发布、再授权与 fork 二次开发。**软件按"现状"（AS IS）提供，不提供任何明示或默示担保**，作者不对任何因使用本软件造成的直接或间接损失负责。
2. **名称与图标边界**：「PersonalAgent」名称及应用图标是原作者的项目标识。为避免误导用户、与官方版本混淆，**建议任何衍生分发版（含 fork 后构建的 APK）更换应用名称与图标**，并修改 `applicationId`（现为 `com.personalagent.app`）使其无法覆盖安装官方包。你应对你自己的分发版本独立负责。
3. **不内置任何 API Key**：本仓库**不包含、也不会提供**智谱 BigModel 的 API Key。使用者需自行到智谱开放平台注册账号、申请 Key 并填入应用设置；模型调用产生的全部费用由使用者自行承担，账号与 Key 的安全由使用者自行负责。
4. **第三方服务与商标**：应用自身不含大模型能力，所有 AI 请求由**用户设备直接**发往智谱开放平台接口（`https://open.bigmodel.cn/api/paas/v4`）。本项目与智谱 / 北京智谱华章科技有限公司**无任何关联、未获其赞助或背书**；「智谱」「GLM」「BigModel」等名称与标识均为相应权利人的商标。使用本应用即需同时遵守智谱开放平台的服务条款。
5. **数据边界**：
   - 全部用户数据（事项、聊天记录、长期记忆、设置、API Key）仅保存在设备本地的 Room 数据库与 SharedPreferences 中；
   - 本项目**没有自建服务器/后端，不集成任何统计分析、广告、崩溃上报 SDK**；
   - 唯一的主动外发网络请求，是把对话内容与图片（在用户主动发送时）直连智谱 API 以获取 AI 回复；
   - TTS 语音播报交由 Android 系统/用户自选的语音引擎处理；
   - JSON 备份仅在用户主动点击导出时生成，并经系统分享面板由用户自行选择接收方，应用不会自动上传。
6. **合法用途与合规责任**：不得将本软件及其衍生版用于任何违反适用法律法规的用途。如果你基于本项目构建、分发自己的应用，**你需要独立承担该应用的隐私政策、用户协议、数据合规、备案/上架资质等全部责任**（例如更换 AI 服务商后须相应更新隐私披露），原作者不对衍生作品的合规性负责。
7. **开源依赖协议**：本项目使用的 AndroidX、Jetpack Compose、Room、OkHttp、kotlinx-coroutines 等组件均遵循 Apache License 2.0；JUnit（EPL 1.0）仅用于测试，不随正式包分发。完整清单见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
8. **隐私政策对应关系**：应用内《隐私政策》《用户协议》随 APK 打包在 `app/src/main/assets/` 中离线加载，项目根目录 [privacy-policy/](privacy-policy/) 保留内容一致的交付副本。上述第 4、5 条描述的数据处理行为与隐私政策正文一致；如对具体数据处理有疑问，以隐私政策正文为准。
9. **联系方式**：安全或隐私问题请邮件联系 `phillight@qq.com`（运营者：Phillight502），请勿在公开 Issue 中提交任何个人信息或密钥。

## 技术栈

- 语言：Kotlin 2.0；UI：Jetpack Compose（Material3，BOM 2024.09）
- 本地存储：Room 2.6（SQLite）+ SharedPreferences + Gson 备份
- 网络：OkHttp 4.12（SSE 流式）+ kotlinx-coroutines
- AI：智谱 GLM 文本/视觉模型 OpenAI 兼容接口 + Function Calling
- 构建：Android Gradle Plugin 8.13、JDK 17、minSdk 24

## Android 权限说明

| 权限 | 用途 |
| --- | --- |
| `INTERNET` / `ACCESS_NETWORK_STATE` | 直连智谱 API；检测网络以触发重试 |
| `CAMERA` | 拍照发给视觉模型（非必须硬件，无相机设备可安装） |
| `POST_NOTIFICATIONS` | Android 13+ 的定时提醒与常驻通知（运行时申请） |
| `USE_EXACT_ALARM` / `SCHEDULE_EXACT_ALARM` | 精确提醒能力自检与兼容兜底（实际提醒走系统级 `setAlarmClock`） |
| `RECEIVE_BOOT_COMPLETED` | 重启/应用更新后重新调度提醒 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 引导用户加入电池优化白名单，降低国产 ROM 杀后台影响 |
| `WAKE_LOCK` | 闹钟广播短暂唤醒期间保活 |

## 构建与运行

环境要求：**JDK 17**、Android Studio（支持 AGP 8.13 的版本）、Android SDK Platform 36。

**Debug 直接运行**（无需签名配置）：

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

**Release 正式包**（R8 代码与资源压缩，需自备签名）：

```bash
cp keystore.properties.example keystore.properties   # 然后填入你自己的 keystore 信息
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

`keystore.properties` 与 `*.jks` 已被 `.gitignore` 忽略，不会入库；没有该文件时 release 签名配置自动跳过，debug 构建不受影响。**请使用你自己生成的 keystore，原作者的签名密钥不公开也无法提供。**

## 项目结构

```
app/src/main/java/org/example/myapplication/
├── data/
│   ├── agent/       AssistantAgent（多轮工具循环）、AgentTools、时间归一化
│   ├── remote/      AiService（OkHttp + SSE，OpenAI 兼容协议）
│   ├── local/       PreferencesManager
│   ├── repository/  事项 / 聊天 / 记忆 / 审计仓库（Room）
│   └── logic/       ScheduleLogic（重复展开、冲突检测、空闲时段）
├── reminder/        闹钟调度、通知、开机/时间变更重调度
├── ui/              Compose 界面：聊天 / 日程 / 设置 / 协议确认页
└── util/            图片工具、TTS
```

> 说明：源码包名保留脚手架时期的 `org.example.myapplication`，应用对外身份（applicationId）为 `com.personalagent.app`。

## 版本历史

完整更新记录见 [CHANGELOG.md](CHANGELOG.md)。开源仓库以 v1.2 作为首个正式版本，v1.2 之前的内测开发记录仅在更新日志中保留文字摘要，随仓库发布的代码即为 v1.2 状态。

## 开源协议

[MIT License](LICENSE) © 2026 Phillight502

---

# English

## Introduction

PersonalAgent is a local-first, memory-enabled AI personal assistant for Android. Tell it several things in one sentence ("Remind me of the meeting at 3pm tomorrow, and save Xiao Wang's phone number"), and it uses LLM Function Calling to extract multiple intents, create items and store long-term memories — then fires reliable on-time reminders.

- Open-source release: **v1.2** (versionCode 3), the first public release
- minSdk 24 (Android 7.0); target/compile SDK 36
- applicationId: `com.personalagent.app`

## Features

- **AI chat**: SSE streaming output, parallel multi-intent handling, camera/gallery image understanding, TTS playback, retry and stop-generation
- **Function Calling toolchain**: 12 tools for item CRUD, complete/cancel and long-term memory; creates and updates require explicit per-item confirmation, deletion always requires a second confirmation
- **Four item types**: schedule / todo / plan / wish, with end time, attendees, work/personal scope, priority, recurrence rules and archive restore
- **Reliable reminders**: system-grade exact alarms via `AlarmManager.setAlarmClock`; "Mark complete / Snooze 10 min" notification actions; recurring items roll forward automatically; rescheduling after reboot and time changes; vendor-ROM keep-alive guidance
- **Persistent schedule notification** showing the current/next item with a live countdown
- **Time intelligence**: natural-language time normalization, free-slot search, AI rescheduling, conflict detection and sanity review
- **Long-term memory**: profile, contacts, anniversaries and notes, stored/retrieved automatically in conversation
- **Privacy & compliance**: first-launch agreement gate, offline in-app Privacy Policy & User Agreement, one-tap consent withdrawal that wipes all local data
- **Data ownership**: Room local storage, JSON backup import/export; the API key never leaves the device

## Boundaries and Disclaimer (read this first)

This is the most important section of the project. By using, copying or modifying this repository, you acknowledge all of the following.

1. **Source license**: the source code is released under the **MIT License** (see [LICENSE](LICENSE)). You may freely use, fork, modify and redistribute it. **The software is provided "AS IS", without warranty of any kind.** The author is not liable for any damages arising from its use.
2. **Name and logo**: "PersonalAgent" and its app icon identify the original project. To avoid impersonating the official build, **derivative distributions (including forked APKs) are advised to change the app name, replace the icon, and change the `applicationId`** (`com.personalagent.app`) so they cannot be installed as an upgrade over the official app. You are solely responsible for your own builds.
3. **No bundled API key**: this repository **does not and will never contain** a Zhipu BigModel API key. Users must register at the Zhipu Open Platform, obtain their own key and paste it into Settings. All model-usage costs are borne by the user, who is also responsible for keeping the key safe.
4. **Third-party service and trademarks**: the app contains no built-in LLM. Every AI request is sent **directly from the user's device** to `https://open.bigmodel.cn/api/paas/v4` (Zhipu Open Platform). This project is **not affiliated with, sponsored or endorsed by Zhipu AI (Beijing Zhipu Huazhang Technology Co., Ltd.)**; "Zhipu", "GLM" and "BigModel" are trademarks of their respective owners. Using the app also requires accepting the Zhipu Open Platform terms of service.
5. **Data boundary**:
   - All user data (items, chats, memories, settings, API key) is stored exclusively on-device via Room and SharedPreferences;
   - There is **no project server/backend and no analytics, ads or crash-reporting SDK**;
   - The only outbound traffic is chat text and images (sent on user action) going directly to the Zhipu API;
   - Text-to-speech is handled by the Android system / a user-chosen speech engine;
   - JSON backups are generated only on explicit user action and shared through the system share sheet — never uploaded automatically.
6. **Lawful use and compliance**: you may not use this software or derivatives for any unlawful purpose. If you build or distribute your own app from this code, **you are solely responsible for its privacy policy, user agreement, data-protection compliance and any regulatory/filing obligations** (e.g. update disclosures after switching AI providers). The author is not responsible for derivative works.
7. **Third-party licenses**: AndroidX, Jetpack Compose, Room, OkHttp and kotlinx-coroutines are Apache License 2.0; JUnit (EPL 1.0) is test-only and not shipped in release APKs. The full list is in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
8. **Privacy policy mapping**: the in-app Privacy Policy and User Agreement are packaged offline in `app/src/main/assets/`, with identical copies under [privacy-policy/](privacy-policy/). Items 4 and 5 above mirror those documents; the full Privacy Policy text prevails for detailed data-processing questions.
9. **Contact**: for security or privacy issues, email `phillight@qq.com` (operator: Phillight502). Never post personal data or API keys in public issues.

## Tech Stack

- Kotlin 2.0; Jetpack Compose (Material3, BOM 2024.09)
- Room 2.6 (SQLite), SharedPreferences, Gson-based backups
- OkHttp 4.12 (SSE streaming), kotlinx-coroutines
- Zhipu GLM text/vision models via the OpenAI-compatible API with Function Calling
- AGP 8.13, JDK 17, minSdk 24

## Permissions

| Permission | Purpose |
| --- | --- |
| `INTERNET` / `ACCESS_NETWORK_STATE` | Direct Zhipu API calls; connectivity detection for retries |
| `CAMERA` | Taking photos to send to the vision model (optional hardware) |
| `POST_NOTIFICATIONS` | Reminders and the persistent status notification (Android 13+, runtime) |
| `USE_EXACT_ALARM` / `SCHEDULE_EXACT_ALARM` | Capability checks/fallback (actual alarms use `setAlarmClock`) |
| `RECEIVE_BOOT_COMPLETED` | Re-schedule reminders after reboot/app update |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Guiding users to disable battery optimization on aggressive vendor ROMs |
| `WAKE_LOCK` | Brief wake lock during alarm broadcast delivery |

## Build

Requirements: **JDK 17**, Android Studio compatible with AGP 8.13, Android SDK Platform 36.

**Debug** (no signing config needed):

```bash
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

**Release** (R8 minification + resource shrinking; bring your own keystore):

```bash
cp keystore.properties.example keystore.properties   # fill in your own keystore values
./gradlew assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```

`keystore.properties` and `*.jks` are git-ignored. Without that file the release signing config is simply skipped; debug builds are unaffected. **Generate and use your own keystore — the original signing key is not published and cannot be provided.**

## Source Layout

```
app/src/main/java/org/example/myapplication/
├── data/
│   ├── agent/       AssistantAgent (multi-turn tool loop), AgentTools, time normalization
│   ├── remote/      AiService (OkHttp + SSE, OpenAI-compatible)
│   ├── local/       PreferencesManager
│   ├── repository/  Item / chat / memory / audit repositories (Room)
│   └── logic/       ScheduleLogic (recurrence, conflicts, free slots)
├── reminder/        Alarm scheduling, notifications, reboot/time rescheduling
├── ui/              Compose screens: chat / schedule / settings / agreement gate
└── util/            Image helpers, TTS
```

Note: the Kotlin package retains the scaffolding name `org.example.myapplication`; the published app identity is `com.personalagent.app`.

## Changelog

See [CHANGELOG.md](CHANGELOG.md). v1.2 is the first public release; the repository ships the v1.2 code state, while pre-v1.2 internal iterations are summarized in the changelog text only.

## License

[MIT License](LICENSE) © 2026 Phillight502
