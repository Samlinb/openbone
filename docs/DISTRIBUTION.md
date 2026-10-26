# 自带运行时的 Windows 发行包

普通用户使用 Windows x64 发行包，无需安装 Java、Maven、.NET SDK 或手动下载应用依赖。安装和日常启动均可离线完成。需要 Windows 自带的 .NET Framework 4.x（安装器）及支持 OpenGL 的显卡驱动；便携版本身不使用安装器。

## 用户入口

- `Openbone-<版本>-windows-x64-portable.zip`：完整解压后双击 `Openbone/Openbone.exe`；保留整个目录，不能只复制 EXE。
- `Openbone-<版本>-windows-x64-setup.exe`：每用户安装，默认 `%LOCALAPPDATA%\Programs\Openbone`，无需管理员权限；安装后从目标目录启动 `Openbone.exe`。同一个安装器提供卸载入口。
- `Openbone-<版本>-windows-x64.sha256`：发行文件的 SHA-256。

恢复和布局数据继续保存在 `%LOCALAPPDATA%\Openbone`；工程放在用户选择的位置。安装不修改 PATH、JAVA_HOME、系统 Java 或 MCP 设置。MCP 服务仍由用户从菜单手动开启。安装器按收据卸载产品文件，保留未列入收据的工程和已修改文件。

## 开发者构建

在 Windows x64 上准备可再分发的 **Eclipse Temurin JDK 17**、Maven 和系统 .NET Framework C# 编译器（Windows 标配路径）。推荐的发布 JDK 为 Temurin，避免将本机来源和许可不明的运行时混入发行包。

```powershell
./scripts/package-windows.ps1
./scripts/verify-windows-package.ps1 -Desktop
```

脚本从 `pom.xml` 读取版本，默认先执行 `mvn -B verify`，再用官方 `jpackage --type app-image` 生成本机启动器及私有运行时，编译已有离线安装器，生成 ZIP、安装 EXE 和校验和到 `target/dist/`。每次打包使用独立的临时工作目录，只复制指定版本的 shaded JAR，不会包含其他旧版 JAR、测试输出、工程或访问令牌。

可用 `-JdkHome <JDK路径>`、`-Maven <mvn.cmd路径>` 指定构建工具；只有已完成验证时才使用 `-SkipBuild`。`-RuntimeHome <运行时路径>` 可选，用于打包已准备好的 Windows x64 Java 17+ 运行时；默认由 jpackage/jlink 生成。自定义运行时必须包含应用所需模块，并保留其许可证。

运行时包含 Swing、管理诊断、HTTP 客户端/服务器、加密、本地化和反射所需模块。应用与图形本地库继续来自 Maven shaded JAR。保留 `runtime/legal/`、应用 `licenses/` 和 JAR 内的 LICENSE/NOTICE。

`Openbone.exe` 是不带控制台的桌面入口；`Openbone-cli.exe` 使用同一运行时，支持原有 `--version`、`--environment`、`--validate-project` 和 `--open` 参数。

## 验证与发布

`verify-windows-package.ps1` 解压实际 ZIP，并在子进程环境中移除 Java 配置、从 PATH 排除 Java，验证：

- 本机启动器返回正确版本；`--environment` 确认使用发行目录中的运行时。
- 无效工程返回退出码 2。
- 含中文和空格的目录内安装、运行、重装和卸载成功，卸载后用户文件保留。
- 指定 `-Desktop` 时，两个原生入口实际打开编辑器窗口并正常关闭，控制台入口还核实实际视口的首次 OpenGL 渲染日志；测试使用隔离的用户状态目录。此检查不等价于像素级 OpenGL 验收；图形回归另用 `mvn -Dtest=McpDesktopTest -Dopenbone.verifyGl=true test`。

GitHub Actions 在 release created 时使用 Windows runner 和 Temurin 17 构建、验证、上传 Windows 发行附件；手动运行 workflow 可取得构建产物，进行发布前检查。Linux job 继续提供开发者 JAR。新增 Windows 产物不表示 macOS/Linux 自带运行时发行包已经验收。

实现依据：[JDK 17 jpackage 文档](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jpackage.html)、[OpenJDK 实现和契约](https://github.com/openjdk/jdk/blob/master/src/jdk.jpackage/share/man/jpackage.md)。本地 Spine `launcher/.../Launcher.java#main` 通过混淆反射分派启动逻辑，字符串和动态常量未完整还原，不能据此确认其原生 JVM 打包方式。本方案复用 JDK 工具和仓库现有安装器，属于 OpenBone 自身的发行能力。
