# 0.3.2 构建与验收准备

## 本机构建

使用 JDK 17、Android SDK 35、Build Tools 35.0.0。build-debug.ps1 执行架构检查、三个 JVM 模块回归、Room 与 ZIP 的 Robolectric 电脑测试、Lint 和 debug 打包；build-release.ps1 进一步执行 release Lint、R8／资源裁剪和 release 签名打包。133 项本地回归通过。脚本校验签名、清单版本、预设与说明文件的逐字节一致性、角色资源、中文 OCR 模型与 PDF 字体，并生成 SHA256SUMS.txt 和 verification.json。

首次 release 构建自动生成项目专用签名，保存于 .signing/qingban-release.jks 与 .signing/release.properties，限制 Windows 文件访问权限并排除版本控制。以后复用同一签名。

请将 .signing 目录另存到铭哥自己保管的安全备份位置。它不应进入代码仓库或发送给模型。缺少任何一个签名文件时，脚本停止，不会擅自生成替代密钥。Android 后续覆盖安装需要一致签名。

## 电脑端真实模型验收

铭哥已选择“先完成本地回归，保留真实模型验收入口”。本轮不执行真实模型请求。

没有项目专用接口和密钥时不执行真实请求，不使用其他程序或智能体运行环境的凭据。已提供独立入口，无需手机。先在调用命令的进程中设置 QINGBAN_MODEL_BASE_URL、QINGBAN_MODEL_NAME、QINGBAN_MODEL_API_KEY；可选 QINGBAN_MODEL_JSON_MODE=false 与 QINGBAN_MODEL_TIMEZONE（默认 Asia/Shanghai）。不要将密钥写入源码或命令日志。

```powershell
. .\scripts\toolchain.ps1
.\gradlew.bat :integration:model:evaluateModel --console=plain
```

包含六项首步理解测试和三项多轮测试，最多 54 次模型请求，按服务商计费。所有操作在独立内存数据中执行，没有连接应用数据库或手机，也不发送私人资料。失败时返回非零退出码。模型通过这些场景仍不意味着所有自然语言指令都能正确理解。

## 边界

当前已在 Android 15 手机上通过基础导航、事项、离线追问、资料检索、旋转恢复与悬浮检查。后台权限、真实音频路线、系统语音服务、OEM 限制和长期待机仍需按 [手机清单](device-acceptance.md) 验证，成功构建不替代这些检查。原素材公开分发授权仍需明确；本轮不发布到商店或 GitHub。
