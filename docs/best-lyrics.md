# 自动使用最佳歌词

入口：**设置 → 歌词 → 自动使用最佳歌词 / 歌词匹配优先级**。

自动使用最佳歌词默认开启，默认优先 **网易云音乐**。可以选择网易云音乐、AMLLDB、QQ 音乐或酷狗音乐；设置自动保存，修改开关或优先级会立即重新匹配当前歌曲。关闭后只使用网易云歌词和项目已有的内置 YRC 修正。

## 匹配行为

- 先加载并显示当前歌曲的网易云歌词，再按优先级寻找最佳结果。
- 指定来源排在最前，其余依次为网易云、AMLLDB、QQ、酷狗，每个来源只出现一次。
- 高置信度的逐字歌词优先于逐行歌词。同类结果采用来源顺序；没有逐字歌词时保留顺序最靠前的可用逐行歌词。
- QQ、酷狗最多比较前 10 个搜索结果：歌名、歌手、专辑分别占 45、25、30 分，时长差异对总分施加惩罚；至少 75 分，且歌名与歌手或专辑匹配，才接受候选。与 Folia 一致，时长差超过 5 秒会低于接受阈值；未提供时长采用 0.9 倍系数。
- 支持简繁中文、假名、标点与合作歌手的名称归一化。Java 版使用 ICU 的文字转换，与 Folia 的 OpenCC / wanakana 实现可能在少数字词上存在差异。
- 网易云直接使用当前歌曲 ID。AMLLDB 同样通过当前网易云 ID 查询官方 API，不进行宽泛文本猜测。QQ 搜索使用匿名客户端的 `search_type=7`；酷狗支持签名搜索和旧搜索接口回退。
- 网易云明确标注纯音乐时停止匹配。来源失败、无结果、歌词损坏或超时不会影响音频播放。

## 格式与运行时

支持 LRC、增强 LRC、WebVTT、网易云 YRC、QQ QRC（包括 XML 包装及其专用 DES 解密）、酷狗 KRC（含嵌入翻译与罗马音）、AMLL TTML（逐字时间、翻译、罗马音、背景和声）。所有时间转为毫秒；翻译和罗马音沿用播放器及桌面 HUD 的现有开关。

搜索步骤限时 3.5 秒，歌词步骤限时 5 秒。酷狗歌词查找与下载共享该步骤预算。请求在虚拟线程执行，切歌或修改设置会取消旧任务；结果在渲染线程再次校验请求代次和当前歌曲，防止覆盖新歌。歌词成功结果最多缓存 200 项、15 分钟；缓存不存失败，也不复用渲染动画状态，切换优先级会重新选择来源。

实现集中于 `src/main/java/tritium/ncm/music/lyrics/`，由 `CloudMusic` 加载歌词，`MusicPreferences` 存储设置，`SettingsPanel` 提供入口。新增键：`lyrics.autoUseBest` 和 `lyrics.preferredSource`。旧配置自动采用上述默认值。

来源与许可证说明位于 `src/main/resources/tritium/licenses/Folia-NOTICE.txt`。

## 验证

```sh
mvn -Dtest='tritium.ncm.music.lyrics.*Test,LyricPreferencesTest' test
mvn -Dtest=SettingsAppearanceRenderingTest -Dtritium.testRendering=true test
```

可选真实网络验证（不使用登录信息，正常测试默认跳过）：

```sh
mvn -Dtest=OnlineLyricProvidersTest#liveAnonymousProviderSmoke -Dtritium.testLyricNetwork=true test
```

第三方接口的可用性和歌曲收录情况会变化；确定性测试使用协议与格式样本覆盖匹配规则、解析、解密、超时、取消、缓存、持久化和界面操作。
