# 16 KB 页对齐（Android 15+ / Google Play 强制）

## 问题

Google Play 报错：

> APK app-arm64-v8a-debug.apk is not compatible with 16 KB devices. Some libraries
> have LOAD segments not aligned at 16 KB boundaries:
> `libleedor.so` `libonnxruntime.so` `libonnxruntime4j_jni.so` `libttsespeak.so`
>
> Starting November 1st, 2025, all new apps and updates ... must support 16 KB page sizes.

原因是这些 `.so` 的 ELF `PT_LOAD` 段 `p_align` 只有 `0x1000`（4 KB）。
16 KB 页设备（部分 Android 15+ 机型）无法加载它们，Play 也会直接拒收。

**注意**：只改 `p_align` 是不行的。16 KB 还要求
`p_offset % 16384 == p_vaddr % 16384`，而原库的第 2、3 个 LOAD 段**不满足**这个同余条件：

```
libttsespeak.so  off=0x000bca00 vaddr=0x000bda00 align=0x1000  16K同余=❌
libonnxruntime.so off=0x00f28580 vaddr=0x00f29580 align=0x1000  16K同余=❌
```

所以必须**真正重新链接**，不能靠改头部糊过去。

---

## 修复方案（3 处）

| 库 | 来源 | 修法 |
|---|---|---|
| `libleedor.so` | 本项目 C 代码 | CMake 加链接参数 `-Wl,-z,max-page-size=16384` |
| `libonnxruntime.so`<br>`libonnxruntime4j_jni.so` | ONNX Runtime AAR | 依赖从 `1.18.0` 升到 **`1.22.0`** |
| `libttsespeak.so` | 第三方预编译 | **从 espeak-ng 官方源码重新编译**，产物改名 `libespeak-ng.so` |

### 1. libleedor.so

[CMakeLists.txt](../app/src/main/cpp/CMakeLists.txt)：

```cmake
target_link_options(leedor PRIVATE "-Wl,-z,max-page-size=16384")
```

NDK r27+ 默认就是 16384，但本项目固定用 NDK 25（默认 4096），所以显式指定，
不依赖 NDK 版本。

### 2. ONNX Runtime

[build.gradle.kts](../app/build.gradle.kts)：

```kotlin
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
```

版本选择的实测依据（解包 AAR 检查 `p_align`）：

| 版本 | `libonnxruntime.so` | `libonnxruntime4j_jni.so` | arm64 合计体积 |
|---|---|---|---|
| 1.18.0（原） | 4096 ❌ | 4096 ❌ | 16 MB |
| 1.20.0 | 16384 ✅ | 4096 ❌ | — |
| **1.22.0** | **16384 ✅** | **16384 ✅** | **17 MB** |
| 1.28.0 | 16384 ✅ | 16384 ✅ | 27 MB |
| 1.30.0 | 16384 ✅ | 16384 ✅ | 32 MB |

**1.22.0 是两者都对齐的最早版本**，从 1.18.0 升级过去体积只增加约 1 MB，
API 变动最小。若想用更新的版本（1.28 / 1.30 也都对齐），直接改版本号即可，
代价是 arm64 体积分别增加约 11 MB / 16 MB。

### 3. espeak-ng（重点）

原来的 `libttsespeak.so` 是第三方预编译二进制（`com.reecedunn.espeak`），
仓库里没有它的源码，无法重新链接。因此改为**从 espeak-ng 官方源码自行编译**。

一键脚本：

```bash
bash docs/build-espeak-ng-android.sh          # 全部 4 个 ABI
bash docs/build-espeak-ng-android.sh arm64-v8a
```

脚本做的事：

1. **先校验数据版本兼容性**（关键，见下）
2. 下载 espeak-ng master 源码（sha256 已记录）
3. 用 NDK 25 交叉编译 `libespeak-ng.so`，带 `-Wl,-z,max-page-size=16384`
4. 校验产物确实 16 KB 对齐
5. 安装到 `app/src/main/jniLibs/<abi>/libespeak-ng.so`

关键 CMake 参数：

```bash
-DBUILD_SHARED_LIBS=ON          # 要 .so
-DCOMPILE_INTONATIONS=OFF       # 不重新编译数据（复用现有的）
-DENABLE_TESTS=OFF
-DUSE_MBROLA=OFF -DUSE_LIBSONIC=OFF -DUSE_LIBPCAUDIO=OFF \
-DUSE_SPEECHPLAYER=OFF -DUSE_ASYNC=OFF    # 关掉所有音频后端
-DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384"
```

#### ⚠️ 数据版本必须匹配

espeak-ng 运行时会校验 `espeak-ng-data` 的版本，不匹配就报
`Wrong version of espeak-ng-data` 并初始化失败。

- 校验逻辑：`src/libespeak-ng/synthdata.c` → `const int version_phdata = 0x014801;`
- 它和 `phondata` 文件**前 4 字节（小端）**比较
- 本项目内置数据实测为 **`0x014801`**，与 master（1.53.0）**完全一致**

所以**现有 19 MB 的 `espeak-ng-data` 可以原样复用，不需要重新编译**。
换 espeak-ng 源码版本时务必先跑一遍脚本里的这个检查。

---

## 验证

### ELF 段对齐

```bash
unzip -o app/build/outputs/apk/debug/app-arm64-v8a-debug.apk 'lib/arm64-v8a/*' -d /tmp/chk
# 检查每个 .so 的 PT_LOAD p_align 是否 >= 16384 且满足同余
```

### APK zip 对齐

```bash
~/Library/Android/sdk/build-tools/36.0.0/zipalign -c -P 16 -v 4 <apk>
```

> AGP 8.5.1+ 会自动对未压缩的 `.so` 做 16 KB zip 对齐，这部分无需手工干预。
> 注意 ELF `p_align`（段对齐）和 APK zip 对齐是**两件不同的事**，都需要满足。

### 修复后的实测结果（arm64-v8a）

```
libespeak-ng.so           align=[16384] 16K=✅
libleedor.so              align=[16384] 16K=✅
libonnxruntime.so         align=[16384] 16K=✅
libonnxruntime4j_jni.so   align=[16384] 16K=✅

zipalign -c -P 16 : Verification successful
```

动态依赖也正确切换了：

```
libleedor.so DT_NEEDED = [liblog.so, libespeak-ng.so, libm.so, libdl.so, libc.so]
                          ↑ 已是新库，libttsespeak.so 已无残留
```

---

## 关于 32 位 ABI

`armeabi-v7a` / `x86` 的 `libonnxruntime4j_jni.so` 仍是 4096 对齐，
**这是上游 ONNX Runtime 自身的构建方式，不影响合规**：

- 16 KB 页大小是**64 位**特性，Play 的检查只针对 `arm64-v8a` / `x86_64`
- Play Console 报错信息里也只列了 `lib/arm64-v8a/*`
- 我们自己的 `libleedor.so` 和 `libespeak-ng.so` 在 4 个 ABI 上**都是 16 KB 对齐**

---

## 顺带解决的问题

替换掉第三方预编译的 `libttsespeak.so` 后：

- ✅ **许可证链路清晰了**：现在有 espeak-ng 的确切源码版本和构建脚本，
  GPL-3.0 的"提供对应源码"义务可以真正履行（见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)）
- ✅ **去掉了来源不明的二进制**：之前那个 `.so` 没有附带许可证文件和源码版本信息
- ✅ **体积更小**：单 ABI 从 2.6 MB 降到约 1.4 MB（新库不含内嵌的 libsonic）
