# 应用内更新协议（ToneUp Word）

本文件是 `ToneUpWord` 与 `ToneUp_read` 共用的更新契约。两个应用各自使用**固定的**发布资产名
（word 为 `ToneUpWord.apk`，read 为 `ToneUP-read.apk`），但 `version.json` 字段名、版本比较
规则、镜像列表顺序必须保持一致；只允许 UI、语言和 APK 文件名层面的差异。

## 1. Release 资产（名字一个字都不能改）

每个正式 Release 必须包含且只依赖这两个资产：

| 资产 | 用途 |
| --- | --- |
| `ToneUpWord.apk` | 应用内下载的安装包（**固定名**） |
| `version.json` | 版本元数据（**固定名**） |

客户端不使用 `api.github.com`，只请求：

```
https://github.com/hbt123-123/ToneUp_word/releases/latest/download/version.json
https://github.com/hbt123-123/ToneUp_word/releases/latest/download/ToneUpWord.apk
```

`latest` 只认 **Published** 的 Release；草稿和 Pre-release 不算。

## 2. version.json 字段

```json
{
  "version": "1.0.3",
  "versionCode": 103,
  "url": "https://github.com/hbt123-123/ToneUp_word/releases/latest/download/ToneUpWord.apk",
  "sha256": "<小写 64 位十六进制>",
  "size": 3277452,
  "notes": "更新说明"
}
```

- `version` / `versionCode` / `url` / `sha256` 必填；`size`、`notes` 可为空。
- `url` 必须是 `https://`，且 host 只能是 `github.com`、`objects.githubusercontent.com`
  或内置镜像之一。
- `sha256` 必须是 64 位十六进制；下载完成后整包校验，不匹配立即删除，绝不安装。
- 未知字段会被忽略，便于以后扩展；旧客户端不会因为新字段崩溃。

## 3. 版本规则

- Release tag 必须等于 `v<versionName>`，例如 `v1.0.3`。
- `versionCode == major*100 + minor*10 + patch`，例如 `1.0.3 → 103`。
- 判断更新顺序：
  1. 双方都有 `versionCode` → 比较 `versionCode`；
  2. 否则回退三段语义版本；
  3. 仍然无法判断 → 视为“无更新”（宁可漏报，不误报）。
- 「跳过此版本」按 `versionCode` 记录；只有更高的 `versionCode` 才会重新提示。

## 4. 下载与安装

- 下载写入 `cacheDir/updates/<versionCode>.apk.part`，长度即断点；校验通过后改名
  `<versionCode>.apk`，只有这个文件能交给安装器。SHA-256 只负责完整性，防篡改的最终边界是
  Android 的「同签名覆盖安装」——新包必须与已安装版本使用同一签名密钥。
- 自动检查每天最多一次（24h），手动检查不受限；**只有成功拿到 manifest 才写入检查时间**，
  失败会在下次启动重试。
- 直连 GitHub 失败时按镜像列表顺序尝试最多 3 个；失败线路冷却 30 分钟。
- 安装永远走系统安装器（`ACTION_VIEW` + `REQUEST_INSTALL_PACKAGES`），不存在静默安装。

## 5. 发布清单

```powershell
# 1. 改 app/build.gradle.kts 的 versionName/versionCode（脚本会校验公式）
# 2. 更新 release-notes.md
# 3. 发布前自检
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
# 4. 出包并生成 version.json
powershell -ExecutionPolicy Bypass -File tool\release.ps1 -AppVersion 1.0.3 -Tag
# 5. git push origin main v1.0.3
# 6. GitHub Release: tag v1.0.3，上传 dist\ToneUpWord.apk + dist\version.json，Publish
# 7. 手工同步 README「下载与版本」表格（脚本不会改 README）
```

完整的 GitHub 上传/验收步骤见 [RELEASE_UPLOAD.md](RELEASE_UPLOAD.md)。

发布后自检：

- 打开 `https://github.com/hbt123-123/ToneUp_word/releases/latest/download/version.json`，
  确认 `versionCode` 比现网安装包大；
- 用旧版本 App 打开「我的 → 检查更新」，确认能看到新版本与更新说明；
- 安装后再次检查，应显示“已是最新版本”。
