# 第三方组件与服务声明 / Third-Party Notices

本项目（PersonalAgent）源码采用 MIT 协议开源。应用在构建与运行过程中使用了下列第三方组件与服务，它们各自遵循其原有开源协议或服务条款。

## 一、随 APK 分发的开源库（编译期依赖）

| 组件 | 版本 | 用途 | 协议 |
| --- | --- | --- | --- |
| AndroidX Core KTX | 1.10.1 | Android 基础 Kotlin 扩展 | Apache License 2.0 |
| AndroidX Lifecycle (runtime-ktx / viewmodel-compose) | 2.6.1 | 生命周期与 ViewModel | Apache License 2.0 |
| AndroidX Activity Compose | 1.8.0 | Activity 与 Compose 集成 | Apache License 2.0 |
| Jetpack Compose（UI / Material3 / material-icons-extended / tooling，BOM 2024.09.00） | BOM 2024.09.00 | 声明式 UI 框架 | Apache License 2.0 |
| AndroidX Room（runtime / ktx） | 2.6.1 | 本地 SQLite 数据库 | Apache License 2.0 |
| OkHttp | 4.12.0 | HTTP / SSE 网络请求 | Apache License 2.0 |
| kotlinx-coroutines-android | 1.8.1 | Kotlin 协程 | Apache License 2.0 |
| Kotlin 标准库（随 Kotlin 2.0.21 插件引入） | 2.0.21 | Kotlin 运行时 | Apache License 2.0 |

Apache License 2.0 全文：https://www.apache.org/licenses/LICENSE-2.0

## 二、仅用于测试、不随正式包分发

| 组件 | 版本 | 协议 |
| --- | --- | --- |
| JUnit | 4.13.2 | Eclipse Public License 1.0 |
| AndroidX Test JUnit | 1.1.5 | Apache License 2.0 |
| AndroidX Espresso | 3.5.1 | Apache License 2.0 |
| Jetpack Compose UI Test | BOM 2024.09.00 | Apache License 2.0 |

## 三、构建工具（不进入 APK）

- Android Gradle Plugin 8.13、Gradle Wrapper、Kotlin 编译插件、KSP：Apache License 2.0（或各自上游协议）。

## 四、运行期第三方服务（非开源库）

- **智谱 BigModel 开放平台（GLM 大模型 API）**：应用自身不包含任何大模型能力，所有 AI 对话、Function Calling 与图片理解均由设备直接请求 `https://open.bigmodel.cn/api/paas/v4` 完成。使用者必须自行注册账号、获取并配置 API Key，自行承担调用费用，并遵守智谱开放平台的服务条款与隐私政策。本项目与智谱 / 北京智谱华章科技有限公司无关联、未获其赞助或背书；「智谱」「GLM」「BigModel」等名称与标识为相应权利人的商标。
- **系统 TTS 语音引擎**：语音播报由设备系统自带或用户自行安装的第三方语音引擎提供，音频文本的处理遵循该引擎自身的隐私规则。
- 应用不集成任何统计分析、广告或崩溃上报 SDK。

本清单与应用内《隐私政策》共同构成本项目的数据与第三方边界说明；如二者表述存在差异，以应用内《隐私政策》中对具体数据处理行为的描述为准。
