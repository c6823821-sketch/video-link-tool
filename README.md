# 视频工具箱 / VideoLinkTool

一个安卓端的多平台链接处理工具，三个功能完全独立：

- **下视频**：只解析并下载无水印源视频，不提取音频，不转录。
- **下音频**：只提取音频，不保存整段视频。
- **转文字**：只输出文字，临时音频处理完成后不保留。

## 支持平台

| 平台 | 视频 | 音频 | 转文字 |
|---|---|---|---|
| 抖音 | 支持，首次需要 App 内刷新验证/Cookie | 支持 | 支持 |
| 快手 | 支持视频作品（图集暂不支持） | 支持 | 支持 |
| 哔哩哔哩 | 支持 | 支持 | 支持 |
| 百度视频/好看视频 | 支持 | 支持 | 支持 |
| 今日头条/西瓜视频 | 支持 | 支持 | 支持 |
| 小红书 | 支持视频笔记（图集暂不支持） | 支持 | 支持 |
| 其他 yt-dlp 支持的站点 | 视站点而定 | 视站点而定 | 支持时可用 |

> “无水印”指下载平台提供的源文件。创作者自己烧录进画面里的水印或字幕，不做去水印处理。

## 手机使用

1. 安装 GitHub Actions 产出的 APK。
2. 把分享链接粘贴进输入框。
3. 选择“下视频 / 下音频 / 转文字”，点开始。
4. 视频和音频输出到手机的“下载 / 视频工具箱”目录；文字输出为 `.txt`。

抖音遇到验证提示时，点 App 里的“抖音验证/Cookie”，在打开的网页里完成验证后点“保存验证状态”。一般不需要真登录，能正常加载页面即可。

## 转文字说明

转文字使用离线 `sherpa-onnx + SenseVoiceSmall`，与下载功能彼此独立。第一次使用会下载约 230MB 的模型，之后离线转写。模型只用于“转文字”，不会在下视频/下音频时启动。

## 本地构建

要求：

- JDK 17
- Android SDK Platform 34
- Android Build Tools 34.0.0

命令：

```bash
./gradlew :app:assembleRelease
```

产物：

```text
app/build/outputs/apk/release/app-release.apk
```

## 第三方组件

- `youtubedl-android`：yt-dlp / Python / FFmpeg 的安卓封装，GPL-3.0。
- `sherpa-onnx`：离线 ASR 运行时，Apache-2.0。
- `SenseVoiceSmall`：离线中文语音识别模型。
- `yt-dlp`：Unlicense。

详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
