# App 防破解加固说明 (Anti-Repackage Hardening)

> 与守护端(CTS-Daemon)的 Ed25519 授权校验配套。App 这层主要防「重打包 / 重签名」:
> 别人改了你的 APK 就必须用他自己的私钥重新签名,签名证书随之改变,运行时一比对即可识破。
>
> **现实预期**:GPL 源码公开 + root 设备,**做不到绝对防破解**;目标是让随手破解失效、抬高成本。
> 真正的授权强保证在守护端的非对称签名(见 CTS-Daemon/HARDENING.md),App 层是纵深防御。

## 这次加了什么
- **签名指纹校验**(`app/src/main/java/com/ktwo/cts/license/Integrity.kt`):运行时取本应用签名证书
  SHA-256,与编译期固定值比对,不符则进入「安装包校验未通过」页,拒绝进入主界面。
- **可选 JNI native 层**(`app/src/main/cpp/ctsguard.cpp`):把哈希+比对放进 `.so`,比 dex 更难 patch。
- **R8 强化**(`app/proguard-rules.pro`):打平包名、改访问修饰符、多遍优化、去调试日志;保留 JNI 绑定。
- **可稳定的发布签名**(`app/build.gradle.kts` + `.github/workflows/build.yml`):支持用「固定发布密钥库」
  签名(签名指纹检测的前提)。

## ⚠️ 关键前提:必须用「固定的发布密钥库」
当前默认仍用 debug 签名 —— **debug key 每次 CI 都不一样,签名指纹无法固定,检测会自动放行(fail-open)。**
所以要真正启用防重签名,你必须做下面这套(一次性):

### 1) 生成发布密钥库(本地,妥善保管,别丢)
```bash
keytool -genkeypair -v -keystore release.jks -alias cts \
  -keyalg RSA -keysize 2048 -validity 10000
```

### 2) 取证书 SHA-256 指纹
```bash
keytool -list -v -keystore release.jks -alias cts | grep "SHA256:"
# 形如 SHA256: AB:CD:...  —— 冒号可留可去,大小写都行,构建脚本会自动规整
```

### 3) 配到 GitHub 仓库 Secrets(Settings → Secrets and variables → Actions)
| Secret | 值 |
|---|---|
| `CTS_KEYSTORE_BASE64` | `base64 -w0 release.jks` 的输出 |
| `CTS_KEYSTORE_PASSWORD` | 密钥库口令 |
| `CTS_KEY_ALIAS` | `cts` |
| `CTS_KEY_PASSWORD` | 密钥口令 |
| `CTS_CERT_SHA256` | 第 2 步的指纹 |

配齐后:CI 会用你的发布密钥库签名,并把指纹编进 APK,重签名检测即生效。
没配时:一切照旧(debug 签名 + 检测放行),CI 不受影响。

### 4)(可选)启用 native 层
本地/CI 构建加参数 `-PctsNative=true`(需要 NDK)。CI 默认不开,避免无 NDK 环境构建失败。
> ⚠️ native 层我无法在此环境实机验证,首次启用请在你的设备/CI 上确认能正常加载 `libctsguard.so`。

## 本地命令行单独传参(不走 CI 时)
```bash
gradle :app:assembleRelease \
  -PctsCertSha256=<指纹> -PctsNative=true \
  -PCTS_KEYSTORE=/abs/release.jks -PCTS_KEYSTORE_PASSWORD=*** \
  -PCTS_KEY_ALIAS=cts -PCTS_KEY_PASSWORD=***
```

## 做不到的事
- 阻止有 root + 逆向能力的人为自己改出能跑的版本(可让其降级/被识别,无法根除)。
- 单靠 App 层保证授权 —— 真正的闸门在守护端 Ed25519 校验。
