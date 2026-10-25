# Openbone

[English](README.md) | [简体中文](README.zh-CN.md) | [繁體中文](README.zh-TW.md)

Openbone 是一款以 Spine 工作流为参考的桌面 2D 骨骼动画编辑器，面向游戏角色、道具和技能特效的制作与预览。项目使用 Java 17、Swing 和 libGDX / LWJGL，实现从骨骼搭建、PNG 附件绑定、网格与权重编辑，到关键帧动画和资源包导出的工作流程。

项目对标 Spine 的骨骼、插槽、皮肤、附件、时间轴和约束等核心概念，并以 **Spine 4.2.22 JSON 导出格式的受支持子集**作为交换格式。目前版本为 `0.1.0-SNAPSHOT`，仍在开发中；兼容范围以当前实现为准，不能视为对 Spine 全功能、全版本的完整替代。

## 主要功能

| 模块 | 当前能力 |
| --- | --- |
| 骨骼编辑 | 创建骨骼、调整父子关系、平移 / 旋转 / 缩放、局部与世界坐标操作 |
| 插槽与附件 | PNG 图片导入、Region 附件、插槽绑定、普通与叠加混合 |
| 网格与权重 | Region 转网格、顶点与 UV 编辑、网格剖分、骨骼绑定和顶点权重编辑 |
| 动画时间轴 | Setup / Animate 模式、动画新建与复制、TRS 关键帧、网格 deform、关键帧复制 / 粘贴 / 拖动 |
| 曲线与播放 | 线性、阶梯和贝塞尔曲线，播放、循环、时间定位与吸附 |
| 资源组织 | 多皮肤、附件切换关键帧、绘制顺序关键帧、事件定义与事件关键帧 |
| 约束 | 单骨 / 双骨 IK、变换约束、路径约束、物理约束及受支持的参数时间轴 |
| 裁剪与路径 | 裁剪附件、路径附件和 Setup 控制点编辑 |
| 工程管理 | 撤销 / 重做、工程保存、含 PNG 的便携工程、自动恢复、窗口布局记忆 |
| 多语言 | 英文、简体中文、繁体中文 |

## 与 Spine 的关系及格式兼容

Openbone 使用自己的可编辑工程格式，支持导入和导出限定范围内的 Spine JSON 数据。项目未包含官方 Spine Runtime 的类或源码。

### 支持的交换格式

- **骨架 JSON**：版本字段 `skeleton.spine`。
- **贴图**：PNG，可通过 JSON 中的 `skeleton.images` 和附件路径加载散图。
- **图集**：读取与 JSON 同目录、同名的 `.atlas`，例如 `character.json` 对应 `character.atlas`；支持受限的旋转与裁边信息。
- **导出资源包**：包含 `skeleton.json`、`skeleton.atlas` 和 PNG 图片，可用于匹配格式的运行时接入。

### 当前兼容边界

| 项目 | 限制 |
| --- | --- |
| 版本与文件类型 | 不支持直接打开 Spine 原生 `.spine` 工程或二进制 `.skel`；其他版本 JSON 不在当前兼容范围内 |
| 骨骼 | 仅支持 `normal` 继承模式；不支持骨骼 shear 字段及 shear 动画轨道 |
| 附件 | 支持 Region、Mesh、Clipping、Path；不支持 linked mesh、bounding box、point 等其他附件类型 |
| 颜色与混合 | 插槽和贴图附件仅接受白色颜色值；混合模式限 `normal` / `additive`，不支持颜色动画 |
| 独立 X / Y 轨道 | 同组独立 X / Y 轨道的关键帧时间必须匹配 |
| Deform | 支持网格 deform；路径和裁剪附件的 deform 尚未实现，不支持加权裁剪 |
| 约束 | 存在求解顺序、依赖关系和数值范围限制，不支持依赖皮肤启用的约束及全局物理时间轴 |
| 图集 | 要求 PNG、`RGBA8888`、非预乘 Alpha、`Nearest` 过滤和无重复；旋转仅支持 0° / 90°，区域 `index` 为 `-1`，不支持自定义字段 |
| 导出 | 骨架必须恰好有一个根骨骼；工程数据还需通过运行时 float32 范围等校验 |

接入游戏时，请使用匹配目标格式的运行时，并在目标引擎中验证实际播放效果。Openbone 的工程 JSON、资源包入口 `current.json` 与 Spine 骨架 JSON 是不同用途的文件。

## 构建与运行

### 环境要求

- JDK 17 或更高版本。
- Maven，且 `mvn` 已加入 `PATH`；仓库目前未提供 Maven Wrapper。
- 能运行 Swing 桌面窗口和 LWJGL OpenGL 视口的图形环境。

首次构建需要下载 Maven 依赖。项目包含桌面本地库，具体操作系统与驱动的可用性需在目标环境验证。

### 从源码启动

在项目根目录执行：

```shell
mvn clean package
java -jar target/openbone-0.1.0-SNAPSHOT.jar
```

构建会生成包含依赖的可执行 JAR。不要使用 `original-openbone-0.1.0-SNAPSHOT.jar` 作为独立发行包。

指定简体中文界面：

```shell
java -Dopenbone.lang=zh-CN -jar target/openbone-0.1.0-SNAPSHOT.jar
```

语言参数支持 `en`、`zh-CN`、`zh-TW`。也可通过界面的语言菜单切换，重启后生效；首次启动默认使用英文。

### 命令行选项

```shell
# 查看版本
java -jar target/openbone-0.1.0-SNAPSHOT.jar --version

# 查看 Java、操作系统、屏幕与用户状态目录等环境信息
java -jar target/openbone-0.1.0-SNAPSHOT.jar --environment

# 启动并打开 Openbone 工程
java -jar target/openbone-0.1.0-SNAPSHOT.jar --open "path/to/character.json"

# 校验 Openbone 工程，输出 schema 版本、对象数量和规范化 SHA-256
java -jar target/openbone-0.1.0-SNAPSHOT.jar --validate-project "path/to/character.json"
```

`--open` 和 `--validate-project` 接受 Openbone 工程文件。导入 Spine 导出的 JSON 请使用编辑器中的“导入 4.2.22 JSON”。工程校验失败时进程返回退出码 `2`。

## 快速上手

以下菜单和按钮名称以简体中文界面为例。

### 1. 搭建骨架并导入图片

1. 启动编辑器，通过“文件 → 新建”创建空白工程，保持在“设置 Setup”模式。
2. 按 `N` 切换到建骨工具，在视口中拖动创建根骨骼。选中已有骨骼后继续建骨，可创建其子骨骼。
3. 选择骨骼，使用 `W` / `E` / `R` 调整位置、旋转和缩放，也可在属性面板输入数值。
4. 选中目标骨骼，通过“文件 → 导入 PNG…”或 `Ctrl+I` 导入图片。编辑器会按当前选择使用或创建插槽，并添加图片附件。
5. 调整附件的位置和大小；需要柔性变形时，在附件属性中使用“转换为可编辑网格”，再编辑顶点、绑定骨骼并设置权重。

建议将整个角色组织在一个根骨骼下，以满足运行时导出的要求。

### 2. 制作关键帧动画

1. 在动画面板新建动画，切换到“动画 Animate”模式。
2. 选中骨骼，将时间定位到 `0` 秒，使用“录 TRS”记录起始姿态。
3. 将时间移动到另一个位置，调整骨骼姿态，再次记录关键帧。
4. 在时间轴选中关键帧，通过“曲线…”设置线性、阶梯或贝塞尔插值；按住 `Ctrl` 可多选关键帧。
5. 点击“播放”，按需启用“循环”。通过“皮肤 / 附件 / 事件 / 顺序…”管理相关资源和离散关键帧。

Setup 用于编辑基础结构和初始姿态，Animate 用于制作动画。调整骨骼后请显式记录需要的关键帧。

### 3. 保存可继续编辑的工程

- **保存 / 另存为**：保存 Openbone 工程 JSON。普通工程引用外部图片，移动工程时需同时维护图片路径。
- **保存便携工程（含 PNG）**：保存工程并复制所需 PNG 到相邻的 `<工程文件名>.assets/` 目录。分享或迁移时，请将工程 JSON 与该目录一起打包。
- **恢复未保存的编辑**：通过文件菜单恢复已有快照，恢复后另存为。自动恢复每 15 秒记录已提交的编辑，未提交的输入框内容和拖动预览不在快照中。

自动恢复和布局数据默认位于 Windows 的 `%LOCALAPPDATA%/Openbone`；无 `LOCALAPPDATA` 环境变量时使用 `~/.openbone`。可用 JVM 参数 `-Dopenbone.userState=路径` 指定其他目录。

### 4. 导入已有 Spine JSON

1. 准备版本为 `3.8.75` 或 `4.2.22` 且符合上述兼容范围的导出 JSON。
2. 将同名 `.atlas` 及其引用的 PNG 放到对应位置；使用散图时，保持 `skeleton.images` 与附件路径能正确解析。相对资源路径需位于导入资源目录内。
3. 选择“文件 → 导入 Spine JSON…”，选中骨架 JSON。也可选择 Openbone 导出资源包的 `current.json`，编辑器会解析并校验资源包。
4. 导入成功后会生成新的未保存工程，请保存为 Openbone 工程，便于后续编辑。

若导入被拒绝，可根据错误中的字段路径检查版本、元数据、附件类型、图集配置及约束限制。仅修改版本号不能转换其他版本的数据。

`3.8.75` 导入会转换 `angle`、相对贝塞尔曲线、约束 mix 字段和 deform 结构；过滤未知字段、无效引用、重复/负时间关键帧，并在报告中列出路径。损坏的附件或时间轴会独立过滤，尽量保留其余有效内容。缺图时保留附件尺寸与资源链接；同名 atlas 的 Linear 过滤会按当前渲染能力转为 Nearest。当前模型不支持的剪切与特殊继承模式等会报告并过滤，不能保证视觉无损。`4.2.22` 继续使用严格校验。

导出版本 `3.8.75` 会写出对应旧格式。物理约束、proportional 路径间距、无法合并的双轴曲线或 mix 会明确拒绝导出。带图片的资源包必须补齐有效 PNG。较新的官方 3.8 Runtime 明确拒绝加载标记为 `3.8.75` 的资源，因此请使用匹配的旧版工具，或选择 `4.2.22` 导出并使用匹配 Runtime。

3.8 导入支持调色板 PNG；通过编辑器或指定版本的导出接口生成资源包时，会将调色板及透明度展开为 RGBA 副本，保持每个像素不变，源 PNG 不被修改。原有严格读取接口继续要求 RGBA。

### 5. 导出游戏资源

颜色与透明度支持：3.8 的 `slots.color` 与 4.2 的 `slots.rgba`、`slots.rgb`、`slots.alpha` 支持导入、播放和导出，包括线性、阶梯、贝塞尔插值和插槽初始色。RGB 与透明度可以独立播放；循环和回拖均从初始状态重新采样。颜色与贴图相乘，透明度参与混合和鼠标拾取。含颜色数据的可编辑工程使用 schema 7，旧工程继续兼容。导出为 3.8 时，独立 RGB/alpha 轨需要相同关键帧时间及可共享的曲线，无法准确表示时会拒绝导出。`dandao_1` 的 14 条颜色轨可往返保留。

可选 OpenGL 验证：`mvn -Dtest=SlotColorRenderTest -Dopenbone.verifyGl=true test`。该测试使用无窗口 Pbuffer 调用实际渲染器，检查贴图乘色和透明度混合，并生成 `target/dandao-color-preview.png` 与可打开的 `target/dandao-color-project.json`。

选择“文件 → 导出 Spine 资源包…”，指定输出目录，再选择 `4.2.22` 或 `3.8.75`。导出结果示例：

```text
export/
├── current.json
└── generation-<uuid>/
    ├── skeleton.json
    ├── skeleton.atlas
    ├── manifest.json
    └── images/
        ├── region_0.png
        └── ...
```

`current.json` 指向最新导出批次，`manifest.json` 记录资源校验信息。向游戏引擎交付时，使用对应 `generation-<uuid>/` 下的 `skeleton.json`、`skeleton.atlas` 和 `images/`，保持它们的相对目录结构；不要将 `current.json` 作为运行时骨架文件加载。

导出器会保留旧批次，通过更新入口文件发布新批次。目前按贴图附件复制 PNG 或原图集页面，不会自动将散图重新紧密打包为单张图集。

## 常用操作

| 操作 | 快捷键 / 鼠标 |
| --- | --- |
| 新建 / 打开 / 保存 | `Ctrl+N` / `Ctrl+O` / `Ctrl+S` |
| 另存为 / 保存便携工程 | `Ctrl+Shift+S` / `Ctrl+Alt+S` |
| 导入 PNG | `Ctrl+I` |
| 导入 JSON / 导出资源包 | `Ctrl+Alt+O` / `Ctrl+Alt+E` |
| 撤销 / 重做 | `Ctrl+Z` / `Ctrl+Y` |
| 选择 / 建骨 | `Q` / `N` |
| 移动 / 旋转 / 缩放 | `W` / `E` / `R` |
| 删除所选 | `Delete` |
| 取消当前拖动 | `Esc` |
| 平移视口 / 缩放视口 | 右键或中键拖动 / 鼠标滚轮 |

## 项目结构

```text
src/main/java/io/github/samlinb/openbone/
├── EditorApplication.java  # 桌面与命令行入口
├── application/           # 编辑命令、历史记录、工程状态与播放控制
├── core/                  # 数据模型、动画采样、数学与约束求解
├── desktop/               # Swing 编辑器、属性面板与时间轴
├── viewport/              # OpenGL 视口、贴图渲染与拾取
├── io/                    # 工程读写、Spine JSON 子集、图集与资源包
└── i18n/                  # 界面语言加载
src/main/resources/        # 翻译、第三方声明与许可文本
packaging/installer/       # Windows 安装器源码
```

开发时可运行 `mvn verify` 执行 Maven 配置中的测试与集成验证流程；实际执行的测试取决于本地检出的测试源码。

## 常见问题

**提示找不到 `mvn` 或 `java`？**

安装 Maven 和 JDK 17+，配置 `PATH` 后重新打开终端，用 `mvn -version` 和 `java -version` 检查。

**图片丢失或迁移工程后看不到附件？**

检查源 PNG 路径；选中附件后重新定位图片，再使用“重新加载图片”。跨机器分享优先使用便携工程，并携带相邻的 `.assets` 目录。

**导入版本正确，却提示不支持的字段？**

当前实现严格接受受支持子集，包括对额外元数据的检查。请按错误提示确认数据是否在兼容范围内；版本相同不代表文件中的所有功能均已支持。

**保存成功，导出却失败？**

工程保存与运行时导出使用不同的校验范围。检查是否只有一个根骨骼、PNG 是否可读取，以及约束、动画和数值是否满足导出限制。

## 第三方声明

依赖声明见 [pom.xml](pom.xml)，第三方许可与声明见 [THIRD_PARTY.txt](src/main/resources/THIRD_PARTY.txt) 和 [licenses](src/main/resources/licenses/)。Spine 名称用于说明工作流参考和数据格式兼容目标。
