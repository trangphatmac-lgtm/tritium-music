# Tritium Music 🎵

一个从 Opai Extension 迁移而来的独立桌面网易云音乐播放器。  
保留原版 LWJGL2 / OpenGL 自绘界面、shader、字体和控件体系，同时加入系统级 HUD、扫码登录、Cookie 登录、歌词翻译/罗马音、音乐缓存管理等桌面体验。✨

> 当前项目目标平台：Windows x64 与 macOS Apple Silicon。  
> 运行目标：Java 21。

## 亮点速览 🚀

- 🎧 **独立桌面播放器**：不再依赖 Opai，不需要 Minecraft/客户端环境。
- 🖼️ **原版自绘 UI**：继续使用 LWJGL2 + OpenGL，保留封面墙、模糊背景、动画、shader 和字体渲染。
- 🔐 **双登录方式**：支持网易云扫码登录，也支持粘贴 raw cookie 登录。
- 📚 **完整主功能**：歌单、搜索、喜欢列表、播放控制、进度拖动、音量调节、下载缓存。
- 📝 **歌词体验**：支持普通歌词、逐字歌词、翻译、罗马音、单行/多行展示。
- 🪟 **系统级 HUD**：`musicInfo`、`musicLyrics`、`musicSpectrum` 已迁移为透明置顶桌面 HUD。
- 🛠️ **HUD 布局编辑**：可开启编辑模式拖动/缩放 HUD，并持久化保存位置。
- 🧹 **缓存管理**：设置页显示当前缓存占用，并提供一键清除缓存。
- 🍎 **Apple Silicon 支持**：内置 macOS arm64 LWJGL2 native runtime，不依赖启动器替换。
- 📦 **可打包分发**：支持 Maven shade fat jar、portable zip/tar.gz，以及 jpackage 安装包。

## 功能清单 ✅

### 主播放器

- 扫码登录网易云账号
- Cookie 登录与持久化
- 首页、歌单、搜索、喜欢列表
- 播放、暂停、上一首、下一首
- 音量调节与播放进度拖动
- 歌词页、逐字歌词、翻译、罗马音
- 封面加载、模糊背景、音乐 toast
- 音乐下载缓存与缓存大小显示

### 系统 HUD

- `musicInfo`：封面、歌名、作者/当前歌词、播放进度、下载状态
- `musicLyrics`：桌面歌词、滚动效果、对齐、阴影、单行模式、翻译/罗马音
- `musicSpectrum`：桌面频谱、矩形/线条样式、指示线、颜色和倍率
- 默认点击穿透；编辑模式下可拖动、缩放、保存布局

## 快速开始 ⚡

### 环境要求

- JDK 21+
- Maven 3.9+
- Windows x64 或 macOS Apple Silicon

### 克隆并构建

```bash
mvn test
mvn package
```

构建完成后，fat jar 位于：

```text
target/tritium-music-1.0.2.jar
```

portable 包位于：

```text
target/tritium-music-1.0.2-portable.zip
target/tritium-music-1.0.2-portable.tar.gz
```

## 运行方式 🏃

### Windows

```bash
java -jar target/tritium-music-1.0.2.jar
```

### macOS Apple Silicon

LWJGL2 在 macOS 上需要从 first thread 启动：

```bash
java -XstartOnFirstThread -jar target/tritium-music-1.0.2.jar
```

### 常用启动参数

```bash
# 3 秒后自动退出，用于 smoke test
java -jar target/tritium-music-1.0.2.jar --smoke-exit-after=3

# 强制显示三种 HUD 的 synthetic preview
java -jar target/tritium-music-1.0.2.jar --hud-smoke --smoke-exit-after=3

# 调整 UI 缩放
java -jar target/tritium-music-1.0.2.jar --ui-scale=1.25
```

macOS 运行上述命令时记得加上 `-XstartOnFirstThread`。

## 打包发布 📦

### Portable 包

```bash
mvn package
```

产物会包含：

- `lib/tritium-music.jar`
- `bin/tritium-music`
- `bin/tritium-music.bat`
- native runtime 来源说明

### jpackage

macOS Apple Silicon：

```bash
mvn -Pjpackage-macos-arm64 package
```

Windows x64：

```bash
mvn -Pjpackage-windows-x64 package
```

产物输出目录：

```text
target/jpackage/
```

注意：jpackage 通常需要在对应平台上执行。例如 Windows 安装包在 Windows 上打，macOS `.dmg` 在 macOS 上打。

## 数据目录 🗂️

应用不会再把 Cookie 和缓存写到当前工作目录。

### Windows

```text
%APPDATA%/Tritium Music
%APPDATA%/Tritium Music/cache/MusicCache
```

### macOS

```text
~/Library/Application Support/Tritium Music
~/Library/Caches/Tritium Music/MusicCache
```

主要文件：

- `NCMCookie.txt`：网易云登录 Cookie
- `preferences.json`：播放器与 HUD 设置
- `MusicCache/`：音乐缓存
- `natives/`：启动时解压的 LWJGL2/JInput native runtime

## Native Runtime 🧩

项目内置 LWJGL2/JInput native 包：

- Windows x64：LWJGL2 + OpenAL + JInput natives
- macOS arm64：预编译 Apple Silicon LWJGL2 + OpenAL natives

native 包来源与 SHA-256 记录在：

```text
src/main/resources/tritium/natives/NATIVE-SOURCES.md
```

启动时 `DesktopNativeLoader` 会按当前 OS/arch 解压 native runtime，并设置 `org.lwjgl.librarypath`。

## 项目结构 🧭

```text
src/main/java/tritium/desktop
  桌面入口、配置、路径、缓存、Cookie、native loader、screen manager

src/main/java/tritium/desktop/hud
  系统级 HUD 管理、平台窗口、状态快照、三个 HUD renderer

src/main/java/tritium/screens/ncm
  主播放器窗口、登录 overlay、歌词页、播放器面板

src/main/java/tritium/ncm
  网易云 API、加密、请求、DTO 与播放逻辑

src/main/java/tritium/rendering
  OpenGL 渲染、字体、shader、动画、纹理与控件体系

src/main/java/repackage
  音频播放相关依赖源码

src/main/resources/tritium
  字体、shader、贴图、native runtime 与测试歌词资源
```

## 测试 🧪

```bash
mvn test
```

当前测试覆盖：

- 应用路径解析
- Cookie 存储
- 偏好设置读写
- HUD 默认值、布局、状态快照
- HUD artwork cache
- 音乐缓存大小与清理
- 歌词解析
- 渲染缩放计算

常用静态检查：

```bash
rg "today\\.opai|nativeinstrumentation|tritium\\.reflection|dynIsland" src/main/java
```

期望生产代码中不再恢复 Opai、native instrumentation、reflection 或 Dynamic Island 相关逻辑。

## 迁移说明 🛤️

Tritium Music 最初是 Opai Extension 中的音乐模块。现在已经迁移为独立桌面应用：

- `ExtensionEntry`、Opai module/widget 注册逻辑已移除
- `OpenAPI` 适配改为本地 desktop facade
- `musicSpectrum`、`musicInfo`、`musicLyrics` 已从 Opai widget 迁移为系统 HUD
- native instrumentation、reflection、Dynamic Island 路径不再保留
- 播放器主窗口仍沿用原 LWJGL2/OpenGL UI，不迁移到 JavaFX/Swing/LWJGL3

## 开发小贴士 💡

- macOS 本地调试一定要加 `-XstartOnFirstThread`。
- HUD 使用 AWT/Swing `JWindow` + Java2D，不占用额外 LWJGL Display。
- HUD 渲染只读 `HudStateSnapshot`，避免直接遍历播放器可变状态。
- OpenGL texture 上传必须走桌面主线程任务队列。
- 修改设置页后，可以通过 `preferences.json` 验证持久化结果。

## 免责声明 📜

本项目仅用于学习、研究与个人使用。音乐资源版权归原版权方所有。请遵守网易云音乐服务条款以及所在地法律法规。

---

Made with LWJGL2, Java2D, caffeine, and a suspicious amount of debugging patience. ☕🎶
