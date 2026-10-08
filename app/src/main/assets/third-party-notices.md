# 第三方依赖与素材说明

此清单记录当前直接使用的主要依赖。各组件仍适用其原许可证；本项目没有重新授予第三方素材许可。

| 组件 | 版本 | 许可或条款 | 用途 |
| --- | --- | --- | --- |
| Kotlin | 2.0.21 | Apache-2.0 | 语言与标准库 |
| kotlinx-coroutines | 1.9.0 | Apache-2.0 | 异步与状态流 |
| kotlinx-serialization | 1.6.3 | Apache-2.0 | 结构化数据 |
| AndroidX / Compose | 见各模块 Gradle 文件 | Apache-2.0 | 手机界面与系统适配 |
| Room | 2.6.1 | Apache-2.0 | 本地数据库 |
| OkHttp | 4.12.0 | Apache-2.0 | 用户配置的模型接口 |
| PDFBox Android | 2.0.27.0 | Apache-2.0，附带资源按原声明 | PDF 正文解析 |
| 中文 ML Kit OCR | 16.0.1 | Google ML Kit 条款 | 本地文字识别 |
| Bouncy Castle（PDFBox 间接依赖） | 1.72 | MIT 风格许可 | 本地 PDF 处理；不配置为网络 TLS 信任器 |
| Robolectric | 4.14.1 | MIT；仅开发测试 | 在电脑运行 Android 数据库与备份测试 |
| JUnit | 4.13.2 | EPL-1.0；仅开发测试 | 自动化回归 |

- Kotlin：https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt
- AndroidX：https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt
- OkHttp：https://github.com/square/okhttp/blob/master/LICENSE.txt
- PDFBox Android：https://github.com/TomRoush/PdfBox-Android
- ML Kit：https://developers.google.com/ml-kit/terms
- Bouncy Castle：https://www.bouncycastle.org/about/license/
- Robolectric：https://github.com/robolectric/robolectric/blob/master/LICENSE

GPT 娘图片沿用铭哥青铃项目中的处理文件，原始来源为 Whom001x/-，详见 ARTWORK.md。仓库中尚未明确原素材的使用授权；当前产物用于开发验收，公开分发前需取得相应许可或替换素材。没有复制表情包仓库或 BongoCat 的运行时代码与模型。

安装包不包含测试依赖。本清单保存于安装包 assets/third-party-notices.md，可在“关于与隐私”查看。上游自带的部分 NOTICE 资源也随包保留；Android 默认打包规则会排除一些通用 META-INF 声明，不能假定所有上游许可证全文都已入包。公开发布前应整理完整的传递依赖许可清单。
