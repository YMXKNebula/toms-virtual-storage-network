# 构建与测试

使用 Java 25 和项目自带的 Gradle 9.4.1 Wrapper。正式依赖为 Tom's Storage 2.12.2、Fabric Loader 0.19.5 和 Fabric API 0.165.0+26.3。

## Windows

在项目根目录运行：

```powershell
./tools/build.ps1 build
```

首次运行需要联网。脚本下载固定版本的 JDK、Tom's Storage 和编译用 Cloth API，并校验哈希；Gradle 下载其余开发依赖。依赖齐全后可以加 `--offline`。

安装包输出到 `build/libs`。主 JAR 用于安装，`-sources.jar` 包含正式源码。测试代码、JDK、依赖 JAR、世界与日志不会打入安装包。

也可自行准备 Java 25，将 `tools/dependencies.json` 中的 Tom's Storage JAR 下载到 `vendor`，并按 `tools/build.ps1` 准备编译用 Cloth API，然后执行 `./gradlew build`。`vendor/maven` 只是可选的本地缓存，公开 Maven 源可解析开发依赖；无需任何整合包。

## 自动化测试

```powershell
./tools/build.ps1 build runGametest '-PtestLabel=release-1.0.0' '-PtestRunDir=run/release-1.0.0' --offline
```

`build` 包含 28 项核心测试。`runGametest` 在独立服务器世界运行 55 项测试，结果位于 `build/gametest-release-1.0.0-results.xml`。这些测试不会启动本地游戏实例。

连接器真实卸载、重载及曲率漏斗连续补货的组合测试位于独立的 [Tom's Storage Additions 仓库](https://github.com/YMXKNebula/toms-storage-additions)，测试命令见该仓库开发说明。

客户端测试使用独立世界和隐藏测试窗口：

```powershell
./tools/prepare-client-test-assets.ps1 -GameAssets '你的基础游戏 assets 目录'
./tools/build.ps1 runClientSmoke --offline
```

它验证终端数量、实际取出和重新存入、木桶状态与材质。若需生成包含曲率漏斗的展示场景，将对应补充包主 JAR 放入 `vendor/compat`，再加 `-PcompatPack`。只放需要测试的模组；此目录不会上传或打包。

验证完成后运行 `./tools/package-release.ps1`，会核对测试结果、正式类、资源及源码包，生成 `dist/1.0.0` 和校验清单。

## 目录

- `src/main`：正式源码和资源。
- `src/test`：核心测试。
- `src/gametest`：服务器集成测试。
- `src/clienttest`：独立客户端测试和展示场景。
- `src/scaletest`：历史大规模测试工具，需要另行准备测试依赖，不属于普通构建步骤。
- `src/launcher`：Windows 中文路径下的开发启动工具。

保存格式和方块 ID 需要兼容现有世界。调整库存或网络权限时，应验证事务回滚、加载变化、拆桶转存和数量守恒。
