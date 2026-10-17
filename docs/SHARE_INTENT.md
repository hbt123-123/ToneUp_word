# Share / Process-Text 外部调用文档

给**其他应用**（浏览器、笔记、阅读器、翻译工具、桌面脚本）调用的对接说明：把一段单词文本直接送到「一潼上岸·单词」，由用户在应用内确认后批量入库并自动补全释义。

> 对应实现：`AndroidManifest.xml`（两个 intent-filter）、`MainActivity.handleShareIntent()`、`ExternalImportViewModel.onSharedText()`。
> 相关计划：`dev/PLAN_CUSTOM_PACKS_GATEWAY.md` §8.2（M5）。

---

## 1. 应用标识

| 项 | 值 |
| --- | --- |
| 应用名（分享面板显示） | `一潼上岸·单词` |
| 包名 / applicationId | `top.toneup.word` |
| 入口 Activity | `top.toneup.word.MainActivity` |
| 启动模式 | `singleTop`（已在前后台时不会重开 Activity） |
| 最低系统 | Android 8.0（API 26） |
| 当前版本 | versionName `1.0.3` / versionCode `4` |

---

## 2. 支持的两个入口

两个 filter 都可以把纯文本送进同一个导入面板，**任选其一即可**。

### 2.1 `ACTION_SEND` + `text/plain`（推荐）

系统分享面板里出现「一潼上岸·单词」。适用于「分享 → 选择应用」的常规交互。

- action：`android.intent.action.SEND`
- category：`android.intent.category.DEFAULT`
- mimeType：`text/plain`
- 数据：`Intent.EXTRA_TEXT`（`CharSequence`）

### 2.2 `ACTION_PROCESS_TEXT` + `text/plain`

出现在文本选择菜单（浏览器「翻译/复制」那一栏）。适用于「选中一段文字 → 直接送过来」，从阅读到导入最快。

- action：`android.intent.action.PROCESS_TEXT`
- category：`android.intent.category.DEFAULT`
- mimeType：`text/plain`
- 数据：`Intent.EXTRA_PROCESS_TEXT`（`CharSequence`）
- 可选：`Intent.EXTRA_PROCESS_TEXT_READONLY`（`Boolean`，选中文本是否只读，本应用不区分处理）

> **注意**：`ACTION_PROCESS_TEXT` 按系统约定由宿主用 `startActivityForResult` 发起。本应用**不返回处理结果**（不调用 `setResult`），因为它的产物是一次导入操作而不是替换后的文本。宿主拿到的 result 为空属于预期行为。

---

## 3. 调用示例

### 3.1 Android（Kotlin）

```kotlin
// ACTION_SEND：显式指定包名，避免弹二次选择器（适用于已知目标的场景）
val sendIntent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_TEXT, "apple, banana\ncherry")
    putExtra(Intent.EXTRA_TITLE, "我的生词表")   // 可选，部分分享面板会展示
    setPackage("top.toneup.word")
}
startActivity(sendIntent)
```

```kotlin
// ACTION_SEND：走系统分享面板，让用户自己选
val intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_TEXT, "apple banana cherry")
}
startActivity(Intent.createChooser(intent, "导入到单词应用"))
```

```kotlin
// ACTION_PROCESS_TEXT：文本选择菜单入口
val processIntent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_PROCESS_TEXT, selectedText)
    putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
    setPackage("top.toneup.word")
}
startActivity(processIntent)
```

### 3.2 Android（Java）

```java
Intent intent = new Intent(Intent.ACTION_SEND);
intent.setType("text/plain");
intent.putExtra(Intent.EXTRA_TEXT, "apple, banana, cherry");
intent.setPackage("top.toneup.word");
startActivity(intent);
```

### 3.3 adb / 终端脚本（自测用）

```bash
# 带显式组件，绕过解析，直接验处理逻辑
adb shell am start -a android.intent.action.SEND -t "text/plain" \
    --es android.intent.extra.TEXT "apple, banana, cherry" \
    -n top.toneup.word/.MainActivity

# 不带 -n，验证 manifest filter 能被系统解析到（若设备上还有别的 text/plain 接收方，会弹选择器）
adb shell am start -a android.intent.action.SEND -t "text/plain" \
    --es android.intent.extra.TEXT "apple banana"
```

```bash
# Process-Text 入口
adb shell am start -a android.intent.action.PROCESS_TEXT -t "text/plain" \
    --es android.intent.extra.PROCESS_TEXT "apple" \
    --ez android.intent.extra.PROCESS_TEXT_READONLY true \
    -n top.toneup.word/.MainActivity
```

### 3.4 网页 / WebView（`intent://` 跳转）

```html
<a href="intent:#Intent;action=android.intent.action.SEND;type=text/plain;S.android.intent.extra.TEXT=apple%20banana%20cherry;end">
  导入到一潼上岸·单词
</a>
```

规则要点：

- 形如 `intent:#Intent;...;end`；无 data URI 时不要写 `intent://`。
- `S.` 前缀表示 `String`/`CharSequence` extra（本应用只需这一类）。
- extra 的值必须 **URL 编码**（空格 `%20`、逗号 `%2C`、换行 `%0A`）。
- 目标未安装时，Chrome 等浏览器会静默失败或跳应用商店，**请自行做兜底提示**。
- Chrome 要求该跳转由用户手势触发（点击），不能由脚本自动发起。

---

## 4. 参数说明

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| action | — | 是 | `ACTION_SEND` 或 `ACTION_PROCESS_TEXT` |
| type / mimeType | String | 是 | 必须为 `text/plain`，其他类型不会被接收 |
| `Intent.EXTRA_TEXT` | `CharSequence` | SEND 时是 | 待导入文本；空白（blank）会被直接忽略 |
| `Intent.EXTRA_PROCESS_TEXT` | `CharSequence` | PROCESS_TEXT 时是 | 同上 |
| `Intent.EXTRA_PROCESS_TEXT_READONLY` | `Boolean` | 否 | 仅 PROCESS_TEXT 使用；本应用不区分 |
| `Intent.EXTRA_TITLE` | `CharSequence` | 否 | 仅 SEND；本应用不读取，仅供宿主自身分享面板展示 |
| `Intent.EXTRA_STREAM` | — | 否 | **不支持**，会被忽略 |

---

## 5. 文本解析规则（发送前请按此校验）

送入的**整段文本**在设备本地解析，按分隔符切分后逐 token 校验。

### 5.1 分隔符

逗号 `,`、分号 `;`、空白、换行、制表符均作为分隔符：`[,;\s\n\r\t]+`。

### 5.2 合法词

必须**以字母开头**，后续仅允许字母、撇号、连字符，且长度 ≤ 64：

```
^[A-Za-z][A-Za-z'-]*$
```

- ✅ `apple`、`don't`、`well-known`、`Apple`
- ❌ `123`（数字开头）、`café`（非 ASCII 字母）、`单词`（中文）、`http://a.com`（含 `:` 与 `/`）、单字母 `a`

### 5.3 过滤与去重

- **只含 C 类噪声的 token 会被丢弃并计数**，不会入库。中文全角标点（`，` `；`）**不是**分隔符，因此整段中文会被合成一个 token 后丢弃——这是刻意的，避免中文内容偷偷混入。
- 默认会额外丢弃**单字母 token**（一个字符），因为它们通常是列表序号或碎片。
- 去重**大小写不敏感**，保留**首次出现的写法**：`Apple, apple` → 只入库一条 `Apple`。

### 5.4 限额

| 项 | 上限 | 超出时行为 |
| --- | --- | --- |
| 单个 token 长度 | 64 字符 | 该 token 被过滤（计入「过滤」） |
| 单次可导入单词数 | 20 000 | 超过则**整体失败**，面板提示「请拆分后再导入」 |
| 全部 token 被过滤 | — | 面板显示「没有识别到单词」错误态 |

---

## 6. 接收方的行为（调用方需要知道的）

1. **弹出导入面板，不直接入库。** 面板展示 `可导入 N · 过滤 M · 重复 K`，由用户选择目标词库（默认 = 当前正在学习的词库，也可现场「新建词库…」）并点「导入并补全」才写入。
2. **失败可见。** 走分享入口时，若文本里一个可导入的词都没有，会弹出「没有识别到单词」面板（而不是静默无反应）。经剪贴板自动识别的那条路径才会静默失败。
3. **入库不依赖网络。** 单词先以「待补充」状态落库，释义/音标由后台网关稍后补全；网关不可用只影响补全，不影响数据。
4. **`singleTop` + 消费 extra。** 应用已在前台时，第二次分享通过 `onNewIntent` 直接进面板，不会叠 Activity；extra 在读取后被 `removeExtra` 消费，避免重建时重复触发。
5. **重复分享会再次弹面板。** 去重标记只针对剪贴板自动识别路径（持久化 SHA-256 哈希），分享路径不做「同内容只弹一次」的限制。

---

## 7. 不支持的类型

| 类型 | 结果 |
| --- | --- |
| `ACTION_SEND` + `EXTRA_STREAM`（图片 / 文件） | 不接收 |
| mimeType 非 `text/plain`（如 `text/html`、`image/*`） | filter 不匹配 |
| 其他 action（`ACTION_VIEW`、`ACTION_CREATE_DOCUMENT` 等） | 不接收 |
| 应用间深度链接 / 自定义 scheme | 未声明，不接收 |

---

## 8. 调用方可用性探测（Android 11+ 必须处理）

Android 11（API 30）起，调用方若用 `packageManager` 查询本应用，需要在自己的 `AndroidManifest.xml` 声明可见性，否则 `resolveActivity` 恒返回 `null`：

```xml
<queries>
    <package android:name="top.toneup.word" />
</queries>
```

或按 intent 维度声明：

```xml
<queries>
    <intent>
        <action android:name="android.intent.action.SEND" />
        <data android:mimeType="text/plain" />
    </intent>
</queries>
```

推荐用 `Intent(ACTION_SEND).setPackage("top.toneup.word")` + `resolveActivity(packageManager) != null` 判断是否安装，再决定走「直接调用」还是「提示未安装」。

---

## 9. 自测清单

- 纯英文单词、逗号分隔 → 正常导入
- 换行分隔的多行单词表 → 正常导入
- 中英混排（中文 + 英文单词）→ 英文入库，中文计入过滤数
- 大小写重复（`Apple, apple`）→ 只入库 1 条，重复数 +1
- 全中文 / 纯 URL / 纯数字 → 显示「没有识别到单词」
- 空字符串或纯空白 → 无面板、无反应
- 超过 20 000 个单词 → 整体失败并提示拆分
- 应用在前台时再次分享 → 不叠 Activity，直接刷新面板
- 断网导入 → 仍成功入库，单词显示「待补充」，联网后自动补全

---

## 10. 隐私约定

送入的文本**只在设备本地解析**，不写日志、不上传。只有用户在面板里确认导入后，后台补全才会把**单个单词 token**（以及可选的网关密钥）发往自建释义网关——原始文本、剪贴板内容和分享内容始终不出本机。详见 `../AGENTS.md` ANTI-PATTERNS 与 `dev/PLAN_CUSTOM_PACKS_GATEWAY.md` §1。
