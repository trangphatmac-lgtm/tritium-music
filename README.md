# Tritium Music

基于 Java 21 与 LWJGL 2 / OpenGL 的跨平台网易云音乐桌面播放器，集成歌单管理、逐字歌词、动态背景和桌面 HUD。通过 Minecraft Injection，还可以将歌曲信息、歌词、频谱与通知同步到兼容的 Minecraft 1.8.9 客户端，并在游戏世界中显示 3D 歌词。

支持 **Windows x64、Linux x64 和 macOS Apple Silicon（ARM64）**，内置简体中文、繁体中文和英文界面，默认使用简体中文。

## 功能一览

### 听歌与管理歌单

- **账号与曲库**：二维码登录、Cookie 登录与保存；浏览首页、创建和收藏的歌单、喜欢的歌曲及音乐网盘，支持在线搜索。
- **播放控制**：播放 / 暂停、上一首 / 下一首、音量、进度拖动、播放模式和“下一首播放”队列。正常退出后保存播放会话，下次启动恢复歌曲、位置、队列与播放状态。
- **流式播放**：MP3、FLAC、WAV、M4A / MP4 在短暂缓冲后开始播放，同时继续下载和解码；支持 AAC 与 16 / 24 位 Apple Lossless。完整下载可复用为本地缓存。
- **智能过渡（AutoMix）**：自然切歌时根据歌曲特征安排衔接，缺少特征时使用交叉淡化；可选完全体模式提供更细的音量与 EQ 控制，保持原速播放。默认关闭。
- **智能音效 / AI 调音大师**：按歌曲获取网易云音效推荐和专曲参数，支持 32 种大师音色，以及低音、高音、人声、氛围和现场增强；包含 EQ、动态处理、混响与立体声处理。默认关闭，可与 AutoMix 同时使用。
- **歌单导出**：将创建或收藏的歌单合并导出为 HTML、Markdown、纯文本、JSON 或 CSV，可选择歌名、歌手、歌曲 ID、链接与时长。
- **缓存管理**：查看音乐缓存占用并清理缓存。

### 音效设置

在 **设置 → 音效 → 音效模式** 选择「智能音效」或「AI 调音大师」，立即应用并保存。大师默认自动推荐，也可手动选择音色（左键下一项、右键上一项）。增强项由系统根据歌曲特征自动筛选，并处理氛围 / 现场互斥，实际生效项目显示在状态行，无需手动开关。手动音色直接使用内置模板；专曲参数在自动推荐时获取。

选择「手动」模式可自行开关低频、高频、人声、氛围和现场增强；选择会保存并在切歌后保留，不受歌曲自动筛选影响。氛围与现场互斥，开启一项会关闭另一项。手动模式直接处理原始音频，无需在线推荐；五项全部关闭时不叠加音效。

### 歌词与视觉效果

- **逐字歌词、翻译与罗马音**：支持自动匹配网易云、AMLLDB、QQ 音乐和酷狗歌词；默认开启自动匹配并优先使用网易云。
- **多种歌词场景**：Normal、花火、绘光、凝彩、镜台、商籁、时计、浮名、莫奈、回环、云阶和流光；可选底部控制栏或迷你控制器。
- **封面浏览**：列表、Coverflow（OldSchool / Mirror / Card）与 Wall 封面墙，支持侧边栏或顶部导航。
- **外观设置**：深色 / 浅色主题、预设与自定义强调色，以及 Static、Blur、Liquid Glass 界面材质。
- **主界面背景**：纯色、图片、静音循环视频、Shader、Latent 动态背景及 Cover 封面背景；Cover 提供 Solid、Blur、Nomand 和 Monet 效果。可选择将背景应用到歌词页。

### 桌面与游戏联动

| 功能 | 说明 |
| --- | --- |
| 桌面 HUD | 歌曲信息、桌面歌词、频谱和切歌通知；默认鼠标穿透，信息 / 歌词 / 频谱可在编辑模式中调整位置与大小 |
| 系统媒体集成 | Windows SMTC、macOS「正在播放」和 Linux MPRIS：同步歌曲、歌手、专辑、封面、进度，响应播放/暂停、上一首/下一首及定位请求 |
| Minecraft Injection | 面向 Windows x64、Linux x64 和 macOS ARM64，通过 JVM Attach 连接兼容的 Minecraft 1.8.9 客户端，显示游戏内 HUD 与世界空间 3D 歌词 |

### Minecraft Injection

使用 JDK Attach API 将 Java Agent 加载到正在运行的游戏中，复用游戏自身的 LWJGL 渲染环境。

进入世界后，点击“编辑 HUD”或 Sprint 的“编辑文字位置”会直接打开半透明编辑界面，无需打开聊天栏。拖动调整位置，普通 HUD 可用滚轮缩放或拖动右下角调整大小；Sprint 文字仅调整位置。按 Esc 保存退出。

使用条件：

- 播放器运行时包含 `jdk.attach` 及对应平台的 JDK 本机库。
- 游戏使用 LWJGL 2 及兼容的 MCP / SRG 类名；不同 Forge / Lunar 构建的兼容性取决于其类名和渲染实现。
- 游戏已经进入主菜单，两个程序以同一系统用户和权限级别运行，游戏 JVM 能读取播放器缓存中的 Agent JAR。Linux/macOS 的沙盒或容器可能隔离进程、临时目录或文件访问。
- 游戏未启用 `-XX:+DisableAttachMechanism`；JDK 9+ 若禁用动态 Agent，需加 `-XX:+EnableDynamicAgentLoading` 并重启游戏（Java 8 不添加此参数）。


## 快速开始

### 环境要求

- **JDK 21 或更高版本**，架构须与操作系统及随包本机库匹配。
- **Maven 3.9+**，从源码构建时需要。
- 可用的桌面图形环境和支持 OpenGL 的显卡驱动。

当前随包本机库覆盖 Windows x64、Linux x64 与 macOS ARM64；其他平台和架构尚未覆盖。音乐服务、账号权限与网络状态会影响歌曲和歌词的可用性。

### 从源码构建

```sh
git clone https://github.com/trangphatmac-lgtm/tritium-music.git
cd tritium-music
mvn clean verify
```

构建会运行默认测试，先构建 Minecraft Agent，再生成包含依赖的主程序 JAR 和便携包。以下文件名以当前 `pom.xml` 中的 **1.0.3** 为例，版本变化后请使用实际输出文件名：

```text
target/
├── tritium-music-1.0.3.jar
├── tritium-music-1.0.3-portable.zip
└── tritium-music-1.0.3-portable.tar.gz
```

### 运行 JAR

Windows / Linux：

```sh
java -jar target/tritium-music-1.0.3.jar
```

macOS Apple Silicon：

```sh
java -XstartOnFirstThread -jar target/tritium-music-1.0.3.jar
```

### 使用便携包

解压后在便携包目录运行：

```powershell
# Windows PowerShell
.\bin\tritium-music.bat
```

```sh
# macOS / Linux；脚本会自动处理 macOS 启动参数
./bin/tritium-music
```


### 常用操作

| 操作 | 按键或手势 |
| --- | --- |
| 播放 / 暂停 | 未在输入框编辑时按空格 |
| 聚焦搜索 | `Ctrl / Cmd + F` 或 `Ctrl / Cmd + G` |
| 封面视图选中 / 打开 | 方向键选择，`Enter` 打开歌单或播放歌曲 |
| 封面视图返回 | `Esc` 优先清除聚焦的筛选、收起展开项或返回上一级 |
| 封面墙移动 / 缩放 | 拖动平移，滚轮纵向移动，`Ctrl + 滚轮` 缩放 |

### 启动参数

参数放在 JAR 文件名之后，也可以传给便携包启动脚本：

| 参数 | 用途 |
| --- | --- |
| `--ui-scale=1.25` | 调整界面缩放比例，与 Retina 像素密度独立 |
| `--smoke-exit-after=3` | 启动约 3 秒后自动退出，用于冒烟检查 |
| `--hud-smoke` | 使用合成内容预览歌曲信息、歌词与频谱 HUD |

## 数据与缓存

配置和缓存存放在用户目录中：

| 平台 | 配置目录 | 缓存目录 |
| --- | --- | --- |
| Windows | `%APPDATA%\Tritium Music` | `%APPDATA%\Tritium Music\cache` |
| macOS | `~/Library/Application Support/Tritium Music` | `~/Library/Caches/Tritium Music` |
| Linux | `$XDG_DATA_HOME/tritium-music`；未设置时为 `~/.local/share/tritium-music` | 配置目录下的 `cache/` |

配置目录中的 `NCMCookie.txt` 保存登录信息，`preferences.json` 保存界面和 HUD 设置，`playback.json` 保存正常退出时的播放会话。缓存目录中的 `MusicCache/` 存储音频，`AutoMixFeatures/` 存储歌曲特征，`natives/` 存储启动时解压的本机库。分享日志或配置时请去除登录 Cookie。


普通构建与运行不需要 Node.js。只有重新生成 Folia 歌词设计数据时，才需要 Node.js 24+ 和对应上游源码，详见 [导入工具说明](tools/folia/README.txt)。

## 系统媒体集成

启动后按当前系统自动启用，无需配置：

- **macOS**：通过 MediaPlayer 接入「正在播放」和系统媒体键，适用于项目支持的 Apple Silicon 环境。封面异步加载，切歌或封面不可用时清除旧封面。
- **Linux**：通过当前用户的 D-Bus session bus 提供 MPRIS 2 接口，供 GNOME、KDE Plasma 等桌面及 `playerctl` 使用；支持音量、相对跳转和按歌曲标识的绝对定位。总线名称为 `org.mpris.MediaPlayer2.tritium.instance<PID>`，多个播放器进程可分别发现。使用自定义裁剪 JRE 时需包含 `jdk.security.auth`。
- **Windows**：继续使用 Windows 10 / 11 SMTC。

媒体键由系统路由到当前选中的播放器，具体展示和按钮取决于桌面环境。Linux 无用户 session bus（例如部分 SSH、容器环境）时自动跳过集成；原生接口初始化失败不会中断音乐播放。MPRIS 不提供远程退出、打开外部 URI、停止播放或变速；暂停请使用 `Pause` / `PlayPause`。

Linux 可选检查（`playerctl` 只用于调试，不是运行依赖）：

```sh
playerctl --list-all
playerctl --player=tritium metadata
playerctl --player=tritium play-pause
playerctl --player=tritium position 10
```

## 测试

```sh
# 默认测试
mvn test

# 实际 OpenGL 渲染检查，需要桌面显示环境
mvn -Dtritium.testRendering=true test

# Windows 系统媒体控制集成检查，需要交互式 Windows 桌面
mvn -Dtritium.testSmtc=true -Dtest=WindowsSmtcTest test

# macOS 原生 MediaPlayer 元数据、封面、目标回调与清理检查，需要桌面会话
mvn -Dtritium.testNowPlaying=true -Dtest=MacNowPlayingTest test

# Linux 实际 D-Bus 通信、发现、属性、命令、信号与退出清理检查
dbus-run-session -- mvn -Dtritium.testMpris=true -Dtest=LinuxMprisIntegrationTest test

# Windows / Linux / macOS 的真实 JVM Attach 检查；先将 JAVA8_HOME 指向 Java 8 安装目录
mvn -Dminecraft.testAttach=true -Dtest=AgentAttachFixtureTest,MinecraftProcessDiscoveryTest,MinecraftConnectionErrorTest test
```

## 平台安装包

在对应平台上使用 JDK 的 `jpackage` 构建；还需要该平台打包工具所需的环境：

```sh
# macOS Apple Silicon：DMG
mvn -Pjpackage-macos-arm64 package

# Windows x64：EXE
mvn -Pjpackage-windows-x64 package
```

产物分别位于 `target/jpackage/macos-arm64/` 和 `target/jpackage/windows-x64/`。Linux 当前可使用 JAR 或便携包，仓库未配置 Linux 安装包 profile。

## 许可证与第三方声明

项目许可证见 [LICENSE](LICENSE)。第三方组件、移植代码、资源来源和相应许可证集中收录在 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，其中 Folia 移植部分保留 AGPL-3.0 条款，其他组件按各自声明适用。
