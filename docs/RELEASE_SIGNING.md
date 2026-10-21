# 发布签名（Release signing）

本文说明如何为「一潼上岸·单词 · ToneUp Word」生成签名包，以及**为什么密钥不会、也绝不能出现在仓库里**。

## 1. 为什么不提交密钥库

* 密钥库（`.jks`）一旦泄露，任何人都能伪造你的应用更新；而 Android 要求**同一应用的所有版本必须用同一密钥签名**，泄泄露后无法“换一把钥匙”——只能换包名重新发布，老用户无法平滑升级。
* 因此本项目：
  * `.gitignore` 忽略 `keystore.properties`、`*.jks`、`*.keystore`、`*.p12`、`*.pfx`；
  * `app/build.gradle.kts` 只从**环境变量**或**本地 `keystore.properties`** 读取签名信息，代码里没有任何口令；
  * 找不到签名信息时，release 构建保持**未签名**（不会失败，也不会偷偷用 debug 密钥签名）。

## 2. 生成密钥库（只做一次，务必备份）

```bash
keytool -genkeypair -v \
  -keystore toneup-release.jks \
  -alias toneup \
  -keyalg RSA -keysize 4096 -validity 10000
```

要点：

* **备份**：把 `.jks` 和口令放进密码管理器 / 加密备份。丢失 = 无法给已发布的应用发更新。
* 口令建议 ≥ 16 位随机字符，不要与任何账号口令相同。
* `-validity 10000` ≈ 27 年；Google Play 的推荐做法是同时启用 Play 应用签名（那是另一套密钥，与本仓库无关）。

## 3. 本机出正式包

在仓库根目录创建 `keystore.properties`（已被 gitignore）：

```properties
storeFile=toneup-release.jks
storePassword=你的密钥库口令
keyAlias=toneup
keyPassword=你的密钥口令
```

也可以完全不建文件，改用环境变量（推荐给脚本/CI）：

| 环境变量 | 含义 |
| --- | --- |
| `TONEUP_KEYSTORE_FILE` | 密钥库文件的绝对路径 |
| `TONEUP_KEYSTORE_PASSWORD` | 密钥库口令 |
| `TONEUP_KEY_ALIAS` | 密钥别名 |
| `TONEUP_KEY_PASSWORD` | 密钥口令 |

然后：

```bash
./gradlew :app:assembleRelease -x lintVitalRelease
# 产物：app/build/outputs/apk/release/app-release.apk（已签名）
```

验证签名（把 `<build-tools>` 换成本机版本）：

```bash
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

不配任何签名信息时，同一命令仍会成功，但产物是 `app-release-unsigned.apk`——**这种包在 Android 上无法安装**，只适合做构建检查。

## 4. 出正式包并发布（手工流程）

仓库**没有** CI workflow（`.github/workflows/` 不存在），发布在本机完成。用发布脚本一步生成
APK 与 `version.json`：

```powershell
# 先把 app/build.gradle.kts 的 versionName/versionCode 改好，并更新 release-notes.md
powershell -ExecutionPolicy Bypass -File tool\release.ps1 -AppVersion 1.0.3 -Tag
```

脚本会校验：

* `-AppVersion` 与 `app/build.gradle.kts` 的 `versionName` 一致；
* `versionCode == major*100 + minor*10 + patch`（如 `1.0.3 → 103`）；
* release APK 已签名（未签名直接报错，不会生成发布物）。

产物在 `dist/`（已 gitignore）：

| 文件 | 说明 |
| --- | --- |
| `ToneUpWord.apk` | 固定资产名，应用内更新下载的就是它 |
| `version.json` | 固定资产名，字段契约见 [`UPDATE_PROTOCOL.md`](UPDATE_PROTOCOL.md) |

然后在 GitHub 新建 Release：

1. Tag 必须是 `v<versionName>`（例如 `v1.0.3`），且与 APK 的 `versionName` 一致；
2. 上传 `dist\ToneUpWord.apk` 与 `dist\version.json`（**名字不能改**）；
3. 选择 **Publish release**（草稿/Pre-release 对客户端不可见）；
4. 用旧版本 App 的「我的 → 检查更新」做一次真机验收。

`versionCode` 是更新判断的第一依据：只改 tag 不改 `versionCode`，已安装用户不会收到更新；
只改 `versionCode` 不改 tag，会让 Release 页面和 App 内显示不一致。发布脚本会拦截前者，
tag 的一致性靠这一步人工确认。

## 5. 检查你是否误提交了密钥

```bash
git ls-files | grep -Ei '\.(jks|keystore|p12|pfx)$|keystore\.properties'
```

输出应为空。若已经提交过：**立即作废该密钥库并重新生成**——从 Git 历史里删除文件并不能阻止已经克隆的人拿到它。
