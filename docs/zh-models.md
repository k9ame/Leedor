# 中文语音模型 — 下载与手动存放

侍读郎 (Leedor) 目前**唯一可用的中文朗读方案**是 Piper 引擎的 `zh_CN-huayan` 语音。
本文件给出精确的下载地址、校验值、Android 存放路径和验证方法。

---

## 1. 各 AI 模型的中文支持情况

**关键区分**：「模型训练过中文」和「本应用能读出中文」是两件事。
模型支持只是必要条件，还需要「字 → 音」（G2P）那一环也支持。

| 引擎 | 模型本身支持中文 | **本应用可用** | 原因 |
|---|---|---|---|
| **Piper** | ✅ **2 个语音** | ✅ **可用** | 模型自带 `espeak.voice = "cmn"`，[PiperEngine.kt](../app/src/main/java/com/leedor/tts/engine/piper/PiperEngine.kt) 对非英语语音直接走 eSpeak 分支 |
| **Kokoro v1.0** | ✅ **8 个语音** | ❌ **不可用** | 模型确实训练了中文，但本应用的 Misaki G2P **只移植了英文** |
| Pocket-TTS | ❌ | ❌ | 模型只训练了英语 |
| Kitten Nano | ❌ | ❌ | 模型只训练了英语 |

### Kokoro 的 8 个中文语音（上游数据）

来自 [Kokoro-82M 官方 VOICES.md](https://huggingface.co/hexgrad/Kokoro-82M/blob/main/VOICES.md)，
`lang_code='z'`，需要 `misaki[zh]`：

| 女声 (4) | 男声 (4) |
|---|---|
| `zf_xiaobei` | `zm_yunjian` |
| `zf_xiaoni` | `zm_yunxi` |
| `zf_xiaoxiao` | `zm_yunxia` |
| `zf_xiaoyi` | `zm_yunyang` |

> 这些语音的官方质量评级是 **C/D**，低于英语语音（多为 A/B）。

### 为什么 Kokoro 中文在本应用不可用

三个硬性缺口，缺一不可：

1. **G2P 缺中文**：`assets/` 下只有 `us_gold` / `us_silver` / `gb_gold` / `gb_silver`
   四个**英文**词典，没有中文词典
2. **语言被硬编码**：[KokoroEngine.kt:297](../app/src/main/java/com/leedor/tts/engine/KokoroEngine.kt#L297)
   写死了 `phonemizer.phonemize(sentence, "en-us")`，不管选的是什么语音
3. **语音未登记**：[VoiceDefinitions.kt](../app/src/main/java/com/leedor/tts/data/VoiceDefinitions.kt)
   的 `KOKORO_VOICES` 只列了 3 个英语语音

要打通 Kokoro 中文，需要移植 `misaki[zh]`（jieba 分词 + pypinyin + 中文词典），
工作量远大于 Piper 方案。**当前建议：用 Piper 的 `zh_CN-huayan`。**

### 界面上的标注

代码里的唯一事实来源是 [ModelLanguageSupport.kt](../app/src/main/java/com/leedor/tts/engine/ModelLanguageSupport.kt)，
界面上分三档显示：

| 标注 | 含义 |
|---|---|
| ✅ 支持中文朗读 | 模型支持且链路可用（目前只有 Piper） |
| ⚠️ 模型有中文语音，但本版本缺中文音素模块 | 模型训练过中文但用不了（Kokoro） |
| ❌ 不支持中文 | 模型只训练了英语（Pocket-TTS / Kitten） |

语音库里 148 个语音中，中文语音会带一个 **`中文`** 徽标，方便直接筛出来。

### 附带好消息

`assets/espeak-ng-data/` 里**已经内置**了普通话所需的全部数据，无需额外下载：

```
cmn_dict      1,566,347 bytes   ← 汉字→拼音词典
cmn           语音定义 (language cmn / zh-cmn / zh)
py_dict           2,409 bytes   ← 拼音
phontab          61,228 bytes
phonindex        46,782 bytes
```

---

## 1.5 三个中文方案对比

| 模型 | 类型 | 大小 | 中文语音数 | 语音克隆 |
|---|---|---|---|---|
| **Piper** | 固定音色 | 19.7 / 60.3 MB | 2 | ❌ |
| **Kokoro v1.1-zh** | 固定音色 | 168 MB | **103** | ❌ |
| **ZipVoice** | **零样本克隆** | 177.5 MB | 任意（克隆） | ✅ |

三个都在 `assets/models.json` 里登记，在「设置 → AI 模型」里选择下载。

### Kokoro v1.1-zh

中文 G2P **不需要 jieba / pypinyin** —— sherpa-onnx 把整条流水线离线预编译进了
`lexicon-zh.txt`（68,003 条），运行时只做**词典最长匹配**。详见
[engine/frontend](../app/src/main/java/com/leedor/tts/engine/frontend/)。

- 语音名从 **ONNX 元数据的 `speaker_names`** 读取，不用硬编码
- 英文音素化复用 APK 内置的 `espeak-ng-data`（版本 `0x014801`，与包内一致）

### ZipVoice（语音克隆）

**必须先录一段参考音频才能合成** —— 这是零样本克隆模型的固有要求。

推理链路比其它引擎长，且有两个容易踩的坑：

1. **vocoder 不直接输出波形** —— 它输出 `mag` / `x` / `y` 三个复数谱分量，
   必须再过一次 **iSTFT** 才是 24kHz 音频（见 [IStft.kt](../app/src/main/java/com/leedor/tts/engine/zipvoice/IStft.kt)）
2. **梅尔频谱的三个参数与直觉不符** —— 周期型 hann 窗、513 个频点、reflect 填充
   （见 [MelSpectrogram.kt](../app/src/main/java/com/leedor/tts/engine/zipvoice/MelSpectrogram.kt)）

参考音频用应用内的录音器录（固定 24kHz，正好匹配，无需重采样）。

---

## 2. 可用的两个中文语音

| 语音 ID | 质量 | 模型大小 | 采样率 | 建议 |
|---|---|---|---|---|
| `zh_CN-huayan-medium` | medium | **60.3 MB** | 16 kHz | 音质更好；CPU 推理较慢，长句可能卡顿 |
| `zh_CN-huayan-x_low` | x_low | **19.7 MB** | 16 kHz | 体积小、速度快，**实时朗读推荐** |

> 两者都是单说话人（女声），都叫 `huayan`。
> 本应用是纯 CPU ONNX 推理，**建议先用 x_low**，觉得音质不够再换 medium。

---

## 3. 下载地址

官方源：`https://huggingface.co/rhasspy/piper-voices/resolve/main/`
**⚠️ 当前网络环境下 huggingface.co 不可达（连接超时），必须用镜像。**

镜像源（实测可用，**但很不稳定**，见下方说明）：
`https://hf-mirror.com/rhasspy/piper-voices/resolve/main/`

> **实测记录**：在本机下载 `zh_CN-huayan-x_low` 时，镜像连续 4 次连接超时
> （每次卡满 20s），第 5 次才成功。大文件能下完，小文件反而更容易卡。
> 脚本已内置 5 次重试 + 卡死检测，**失败是正常的，让它自己重试即可**。

### 文件清单

| # | 文件（相对路径） | 大小 | MD5 |
|---|---|---|---|
| 1 | `zh/zh_CN/huayan/medium/zh_CN-huayan-medium.onnx` | 63,201,294 | `40cdb7930ff91b81574d5f0489e076ea` |
| 2 | `zh/zh_CN/huayan/medium/zh_CN-huayan-medium.onnx.json` | 4,822 | `1fda3ec1d0d3a5a74064397ea8fe0af0` |
| 3 | `zh/zh_CN/huayan/x_low/zh_CN-huayan-x_low.onnx` | 20,628,813 | `2b96570db6becd09814a608c8d14a64f` |
| 4 | `zh/zh_CN/huayan/x_low/zh_CN-huayan-x_low.onnx.json` | 4,164 | `39efce50e05f04893ae656f9008698f7` |

### 直接链接（镜像）

```
https://hf-mirror.com/rhasspy/piper-voices/resolve/main/zh/zh_CN/huayan/medium/zh_CN-huayan-medium.onnx
https://hf-mirror.com/rhasspy/piper-voices/resolve/main/zh/zh_CN/huayan/medium/zh_CN-huayan-medium.onnx.json
https://hf-mirror.com/rhasspy/piper-voices/resolve/main/zh/zh_CN/huayan/x_low/zh_CN-huayan-x_low.onnx
https://hf-mirror.com/rhasspy/piper-voices/resolve/main/zh/zh_CN/huayan/x_low/zh_CN-huayan-x_low.onnx.json
```

---

## 4. ⚠️ 存放位置（这里最容易踩坑）

包名是 `com.leedor.tts`，所以设备上的路径是：

```
/sdcard/Android/data/com.leedor.tts/files/piper_downloads/
```

对应代码里 `context.getExternalFilesDir("piper_downloads")`。

### 文件名必须精确匹配

```
piper_downloads/
├── zh_CN-huayan-medium.onnx          ← 必须叫这个名
└── zh_CN-huayan-medium.onnx.json     ← 必须叫这个名
```

`<voiceId>.onnx`，`voiceId` 就是上面的语音 ID，**不能多也不能少字符**。

### ⚠️ 必须放 external 路径，不能放 internal

[VoiceRepository.kt](../app/src/main/java/com/leedor/tts/data/VoiceRepository.kt) 里两个方法的查找范围**不一致**：

| 方法 | 查找位置 |
|---|---|
| `getDownloadState()` (L113) | **只查** `piper_downloads/` |
| `getLocalPath()` (L133) | 先查 `piper_downloads/`，再回退 `filesDir/piper/` |

而 [VoicesViewModel.kt:387](../app/src/main/java/com/leedor/tts/ui/viewmodel/VoicesViewModel.kt#L387) 要求 `downloadState == Downloaded` 才允许选中该语音。

**结论：放到 `files/piper/` 虽然能出声，但界面上无法选中它。必须放 `piper_downloads/`。**

### 推送命令

```bash
# 先确认设备已连接且应用已安装（目录由应用创建）
adb shell mkdir -p /sdcard/Android/data/com.leedor.tts/files/piper_downloads

adb push zh_CN-huayan-x_low.onnx      /sdcard/Android/data/com.leedor.tts/files/piper_downloads/
adb push zh_CN-huayan-x_low.onnx.json /sdcard/Android/data/com.leedor.tts/files/piper_downloads/
```

> Android 11+ 起文件管理器无法浏览 `Android/data/`，但 `adb push` 可以。

---

## 5. 下载并校验

用同目录下的脚本：

```bash
bash docs/download-zh-models.sh x_low      # 只下 x_low（19.7 MB，推荐先试）
bash docs/download-zh-models.sh medium     # 只下 medium（60.3 MB）
bash docs/download-zh-models.sh both
bash docs/download-zh-models.sh x_low --push   # 下完顺带 adb push
```

脚本会自动：走镜像 → 校验 MD5 → 不一致则报错退出。

---

## 6. 在应用里启用

1. 打开侍读郎 → 底部 **Voices** 页
2. 语言筛选里找 **Chinese (China)**，或直接搜 `huayan`
3. 此时应显示为**已下载**（因为我们手动放了文件）
4. 点选它 → 会自动写入：
   - `currentVoice = zh_CN-huayan-x_low`
   - `currentModel = piper_zh_CN-huayan-x_low`
5. 回到系统 **设置 → 无障碍 → 文字转语音**，确认引擎选的是「侍读郎」，然后点「试听」

---

## 7. 已知限制

- ~~**应用内的下载按钮用不了**~~ —— **已修复**。原先所有 URL 都硬编码成
  `huggingface.co`，该域名在部分网络下完全不可达。现在由
  [DownloadHosts.kt](../app/src/main/java/com/leedor/tts/data/DownloadHosts.kt) 统一做
  **镜像优先、官方兜底**的自动回退，并在「设置 → 下载源」里可手动切换。
  手动存放（本文档的方式）仍然可用，只是不再是唯一办法。
- **eSpeak 汉字覆盖有限**：`cmn_dict` 是 espeak-ng 的通用词典，生僻字、多音字、专有名词可能读错。这是 Piper/eSpeak 方案的固有上限，不是本项目的 bug。
- **数字、英文混排**：中文语音遇到阿拉伯数字/英文缩写时，eSpeak 的 `cmn` 按拼音规则处理，效果需要实测。
- **Kokoro / Kitten 模型仍指向上游 GitHub**：它们的下载地址是
  `github.com/siva-sub/NekoSpeak/releases`（GitHub 一般可达，所以能下）。
  若你想彻底去上游化，需要自己 re-host 这两个模型再改 URL。

---

## 8. 语言层现状（已改造完成）

`NekoTtsService` 的语言层已支持中文，映射规则集中在
[VoiceLocales.kt](../app/src/main/java/com/leedor/tts/engine/VoiceLocales.kt)：

| 回调 | 现在的行为 |
|---|---|
| `onIsLanguageAvailable` | 接受 `zh` / `zho` / `chi` / `cmn` / `chs`（中文）和 `en` / `eng`（英文）；按实际可用语音回报 `LANG_AVAILABLE` / `LANG_COUNTRY_AVAILABLE` |
| `onGetLanguage` | 依据当前语音的真实 Locale 回报（中文语音 → `zho/CHN`） |
| `onGetVoices` | 按语音 ID 推断 Locale（Piper 取 `zh_CN-…` 前缀，Kokoro 按 `af_`/`bm_` 前缀），不再一律报 `en-US` |
| `onGetDefaultVoiceNameFor` | 按请求语言挑选语音；请求中文但尚未下载中文语音时回传 `zh_CN-huayan-x_low`，不再静默回落到英文语音 |
| `onSynthesizeText` | 中文请求会优先选中中文语音，避免"中文交给英文语音念" |
| `CheckVoiceData` | 上报 `zho-CHN` + `eng-*` |

另外加了**模型回退链**（[PrefsManager.FALLBACK_MODELS](../app/src/main/java/com/leedor/tts/data/PrefsManager.kt)）：
`piper_zh_CN-huayan-x_low` → `piper_zh_CN-huayan-medium` → `piper_en_US-amy-low`（随 APK 打包）。
即使用户没放中文模型，引擎也能回退到内置英文语音，不会一句话都读不出来。

---

## 9. 界面语言

应用已做中英双语（310 条 × 2）：

- `res/values/` —— **中文（默认兜底）**
- `res/values-en/` —— **英文**

Android 13+ 可以在「设置 → 应用 → 侍读郎 → 语言」里单独为本应用选择语言
（声明在 [locales_config.xml](../app/src/main/res/xml/locales_config.xml)）；
Android 12 及以下跟随系统语言。

> 注意：设备语言是**英文**时，应用会走 `values-en/` 显示英文。
> 想让它在所有语言下都显示中文，删掉 `res/values-en/` 即可（或把系统/应用语言设为中文）。
