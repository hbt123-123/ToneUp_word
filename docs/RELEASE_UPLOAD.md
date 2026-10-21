# 上传安装包到 GitHub Release（ToneUp Word）

本文只讲“APK 已经构建好之后，如何把它上传到 GitHub Release，让应用内更新能读到它”。
签名/密钥细节见 [RELEASE_SIGNING.md](RELEASE_SIGNING.md)，更新字段契约见 [UPDATE_PROTOCOL.md](UPDATE_PROTOCOL.md)。

## 0. 本次发布参数（先核对）

| 项目 | 值 |
| --- | --- |
| versionName | `1.0.3` |
| versionCode | `103`（`1*100 + 0*10 + 3`） |
| Release tag | `v1.0.3` |
| APK 资产名 | `ToneUpWord.apk`（固定，不能改） |
| 元数据资产名 | `version.json`（固定，不能改） |
| 旧版本 | GitHub Release `v0.2`，APK 真实 versionName/Code 为 `0.2 / 2` |
| 自动更新 | 从 1.0.3 起启用；v0.2 没有更新器，用户必须手动安装一次 |

固定下载地址：

```
https://github.com/hbt123-123/ToneUp_word/releases/latest/download/version.json
https://github.com/hbt123-123/ToneUp_word/releases/latest/download/ToneUpWord.apk
```

> **推荐顺序**：改版本号和 `release-notes.md` → **提交并推送 `main`** → 跑
> `release.ps1 -Tag` → 校验 `dist/` → 推送 tag → GitHub Release 上传并 Publish → 真机验收。
> **不要在工作区有未提交改动时跑 `-Tag`**：tag 指向 HEAD，不会包含未提交的文件。

## 1. 发布前检查

1. **签名材料必须是 v0.2 同款。** 线上 v0.2 是另一台机器签名的；本仓库不提交
   `keystore.properties` / `*.jks`。请把原 keystore 放回本机，并在根目录创建
   `keystore.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`），
   或设置 `TONEUP_KEYSTORE_FILE` / `TONEUP_KEYSTORE_PASSWORD` / `TONEUP_KEY_ALIAS` /
   `TONEUP_KEY_PASSWORD`。
   如果找不回原密钥，新包无法覆盖安装，用户只能卸载重装并丢本地数据。
2. **版本号只改 `app/build.gradle.kts` 的 `versionName` / `versionCode`。** 当前必须是
   `1.0.3 / 103`，且 `versionCode == major*100 + minor*10 + patch`；发布脚本会校验。
3. **更新 `release-notes.md`。** 它会进入 `version.json` 的 `notes` 和更新对话框；
   每次发版替换成当前版本说明，不要把历史版本累计在里面。
4. **提交并推送代码。** 发布脚本会用 `git tag` 给 HEAD 打 tag；工作区有未提交改动时，
   tag 不包含这些改动。先确认 `git status` 干净，再提交并推送：

   ```powershell
   git add -A
   git commit -m "release: v1.0.3"
   git push origin main
   ```
5. **本地自检：**

   ```powershell
   .\gradlew.bat :app:testDebugUnitTest
   .\gradlew.bat :app:assembleDebug
   ```

## 2. 生成两个发布资产

```powershell
powershell -ExecutionPolicy Bypass -File tool\release.ps1 -AppVersion 1.0.3 -Tag
```

脚本会：

1. 从 `app/build.gradle.kts` 读取并校验 versionName / versionCode；
2. 执行 `.\gradlew.bat :app:assembleRelease -x lintVitalRelease`；
3. 检查产物是已签名的 `app-release.apk`（未签名直接报错）；
4. 计算 APK 的 SHA-256 与字节大小；
5. 生成 `dist\version.json`；
6. 把 APK 复制为 `dist\ToneUpWord.apk`；
7. 打印上传清单（Tag、正文、两个资产名、sha256、size）。

产物（`dist/` 已 gitignore，不要提交）：

```
dist\ToneUpWord.apk
dist\version.json
```

`version.json` 的 `url` 固定指向 `releases/latest/download/ToneUpWord.apk`，不要手改成别的地址。

## 3. 上传前本地校验

1. 看 `version.json`：

   ```powershell
   Get-Content dist\version.json -Raw
   ```

   必须包含：`version=1.0.3`、`versionCode=103`、`url` 指向固定 APK 地址、64 位小写 `sha256`、
   `size`、`notes`。

2. 核对哈希与大小：

   ```powershell
   (Get-FileHash dist\ToneUpWord.apk -Algorithm SHA256).Hash.ToLower()
   (Get-Item dist\ToneUpWord.apk).Length
   ```

   与 `version.json` 的 `sha256` / `size` 逐字一致。**生成之后不要再重新构建 APK**，
   否则哈希和大小会变，客户端会拒绝安装。

3. 校验签名，并与 v0.2 的证书对比：

   ```powershell
   apksigner verify --print-certs dist\ToneUpWord.apk
   ```

   证书必须与现网 v0.2 安装包一致；不一致时不要上传，先找回原密钥。

4. 确认 `103` 大于用户当前安装的 versionCode（v0.2 是 `2`）。不递增，客户端会认为“已是最新”。

## 4. 推送 tag

如果第 1 步已经提交并推送 `main`，这里只需推送第 2 步生成的 tag：

```powershell
git push origin v1.0.3
```

如果第 2 步没有用 `-Tag`，补打并推送：

```powershell
git tag v1.0.3
git push origin v1.0.3
```

如果 tag 已经打了但之后又改了代码：

- tag **还没有推送**到 GitHub：删掉本地 tag，提交后重新打：
  ```powershell
  git tag -d v1.0.3
  # 提交后
  git tag v1.0.3
  git push origin v1.0.3
  ```
- tag **已经推送或已经创建 Release**：不要强推、不要重打；直接升 patch（`1.0.4 / 104`）
  走一遍新发布。

不要复用已经发布过的 tag；任何重新打包都要升 patch 版本（例如 `1.0.4 / 104`）。

## 5. 在 GitHub 创建 Release

1. 打开 `https://github.com/hbt123-123/ToneUp_word/releases`；
2. 点击 **Draft a new release**；
3. **Choose a tag** 选择刚推送的 `v1.0.3`（若列表里没有，先确认 `git push origin v1.0.3` 成功）；
4. Target 选 `main`；
5. Title 填 `v1.0.3`；
6. 描述粘贴 `release-notes.md` 的内容（应用内备注来自 `version.json`，这里保持人类可读即可）；
7. 上传两个资产，文件名必须精确：
   - `ToneUpWord.apk`
   - `version.json`
8. **不要勾选** “Set as a pre-release”；保持 “Set as the latest release”；
9. 点击 **Publish release**。草稿和 Pre-release 都不会进入 `releases/latest`。

有 `gh` CLI 时也可以：

```powershell
gh release create v1.0.3 dist\ToneUpWord.apk dist\version.json `
  --title "v1.0.3" --notes-file release-notes.md --latest
```

## 6. 发布后验证

1. 浏览器打开固定地址，确认 200 且内容正确：

   ```
   https://github.com/hbt123-123/ToneUp_word/releases/latest/download/version.json
   ```

2. **v0.2 用户没有更新器**，首个新版本只能手动安装：把 `dist\ToneUpWord.apk` 传到装有 v0.2 的设备上覆盖安装
   （必须同签名），打开「关于」页确认显示 `1.0.3`，并确认原有学习数据还在。
3. **自动更新链路要等下一个版本才能真实验证**：先让设备停留在 `1.0.3`，再准备一个 `1.0.4 / 104` 的
   Release（或用测试用的更高 versionCode），在 `1.0.3` 的「我的 → 检查更新」里应看到 `v1.0.4`。
   只看首个版本无法证明“旧版本能自动收到更新”。
4. 再次「检查更新」，应显示“已是最新版本”。
5. 更新 README 的「下载与版本」表格（脚本不会自动改 README）。

## 7. 常见问题

| 现象 | 原因 / 处理 |
| --- | --- |
| 应用一直显示“已是最新” | `version.json` 的 `versionCode` 没有大于已安装版本；或上传的是旧 `version.json` |
| 检查更新失败 | Release 不是 Published、资产缺失、或网络线路都不通；先确认固定 URL 能打开 |
| 下载后安装失败 | APK 签名与已安装版本不一致；换回 v0.2 同款 keystore，或让用户卸载重装 |
| 安装包 404 | 资产名不是精确的 `ToneUpWord.apk`；重新上传并改名 |
| `version.json` 解析失败 | 手改过 JSON 或上传了损坏文件；重新用脚本生成并上传 |
| 更新说明是旧的 | `release-notes.md` 没改，或先改了 notes 又用了旧的 `version.json`；重新生成 |
| 替换已发布版本的资产 | 不要这样做；发布新的 patch 版本，避免 CDN 缓存和已下载的旧 manifest 不一致 |
| tag 已存在 | 不复用 tag；升 patch 版本后重新发布 |
| 发布包是 unsigned | 没配 keystore；按 [RELEASE_SIGNING.md](RELEASE_SIGNING.md) 配好后重新跑脚本 |

## 8. 红线

- **绝不提交**：`dist/`、`*.apk`、`*.jks`、`keystore.properties`。
- **同一应用的所有版本必须同签名**；keystore 丢失等于无法平滑升级，务必备份。
- 按版本顺序发布：GitHub 的 `latest` 取最近发布的非预发布 Release，不是最高版本号；
  不要在新版本之后补发旧版本，否则 `latest` 会指回旧包。
- `sha256` 只保证下载完整性；防篡改的最终边界是 Android 的同签名覆盖安装。
- 不要分发 debug APK 或 unsigned release APK：它们无法覆盖安装正式版。
- 不要在发布后再改代码/重新构建；需要改就升 patch 版本重新走一遍。
- 不要用 GitHub 的 “Auto-generate release notes” 代替 `release-notes.md`，否则 App 内备注与页面不一致。
