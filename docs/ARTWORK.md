# 素材来源

用户指定以 GPT 娘为主，参考 https://github.com/Whom001x/- 。

当前六张透明 PNG 从用户自己的青铃项目已处理素材中取得：

| 本项目 | 青铃源文件 |
| --- | --- |
| gpt_idle.png | mascot_noon.png |
| gpt_welcome.png | mascot_morning.png |
| gpt_thinking.png | mascot_afternoon.png |
| gpt_waiting.png | mascot_evening.png |
| gpt_sleep.png | mascot_night.png |
| gpt_icon.png | character_icon.png |

来源目录：https://github.com/Nichinichisou0706/qingling-alarm/tree/main/app/src/main/res/drawable-nodpi

青铃的处理说明：https://github.com/Nichinichisou0706/qingling-alarm/blob/main/docs/ARTWORK.md

原素材来自 Whom001x/- 的 generated 目录；当前项目没有重新授予许可。没有复制表情包仓库或 BongoCat 的代码与素材。布局、图标组合和主题在新工程中编写。

## 0.3.1 单角色整理

青铃的 mascot_noon 与 mascot_evening 图各含两位角色。按铭哥要求，轻伴的 gpt_idle.png 与 gpt_waiting.png 各只保留左侧角色，并放大对齐至其他状态图的透明画布。处理是裁切与缩放，没有新生成或下载角色。

原始双人图保留在 artwork/originals，仅作为素材来源，不进入 APK。scripts/prepare-single-character.py 使用两位角色之间的透明边界分离图像；重复运行始终读取原始副本，避免重复裁切。六图预览为 dist/character-single-preview.png。其余四张图片保持原状。
