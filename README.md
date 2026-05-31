# OnePlus V Pro · 接管助手

> 替代 Scene 接管,按应用画像自动切换调度模式
>
> UI 风格参考 [SukiSU-Ultra](https://github.com/SukiSU-Ultra/SukiSU-Ultra) (GPL-3.0)
> 接管逻辑参考 [helloklf/vtools](https://github.com/helloklf/vtools) (GPL-3.0)

## 工作方式

监听屏幕开关 + 前台 App 变化,按 **App 画像 → 全局默认 → 灭屏模式** 决定要切的模式,
通过 root 调用 `/data/powercfg.sh <mode>`(Scene 接管协议)由 CoreTurboScheduler 模块执行。

### Scene 风格的接管细节

- **输入法包永远忽略**(切到搜狗/讯飞不动模式)
- **系统 UI / 启动器 / 锁屏永远忽略**(builtin ignored list)
- **用户黑名单**:在 Apps 屏内每个 App 长按可加入,加入后不接管
- **STRICT 同模式重发**:某些游戏切回同模式需要重发,Settings 可开
- **灭屏切设定模式**(默认 powersave),亮屏强制重发当前前台 App 的画像

### 前台 App 双轨探测

- 有 root → `dumpsys window | grep mCurrentFocus`(最快)
- 无 root → `UsageStatsManager`(需要"查看使用情况"权限)

## 编译

### 方式 1 — GitHub Actions(推荐,免本地装 SDK)

1. 在 GitHub 新建一个仓库
2. 把这个 zip 解压后 `git push` 上去
3. Actions 自动跑 `gradle assembleDebug + assembleRelease`
4. 5 分钟后在 Actions 页面下载 `app-debug.apk` / `app-release.apk`

我已经在 `.github/workflows/build.yml` 写好了 workflow,push 上去就触发。

### 方式 2 — 本地 Android Studio

打开整个 `cts_app/` 文件夹,Android Studio 会自动 sync,然后:
- Build → Build APK(s) → 出 debug APK
- Build → Generate Signed Bundle / APK → 出签名 release APK

需要:Android Studio Koala/Ladybug+ 和 JDK 17。

## 项目结构

```
app/src/main/
├── AndroidManifest.xml
├── java/com/ktwo/cts/
│   ├── CtsApp.kt                  Application + libsu 全局
│   ├── data/
│   │   ├── Mode.kt                5 个模式枚举 (FOLLOW/POWERSAVE/BALANCE/PERFORMANCE/FAST)
│   │   └── Prefs.kt               DataStore 持久化 + PrefsExt(strict/blacklist)
│   ├── root/
│   │   └── RootShell.kt           libsu 封装,触发 powercfg.sh / switch.sh
│   ├── service/
│   │   ├── ScheduleService.kt     前台服务,Scene 风格接管(IME 过滤+黑名单+STRICT)
│   │   ├── ForegroundDetector.kt  双轨探测(dumpsys/UsageStats)
│   │   └── BootReceiver.kt        开机自启
│   └── ui/   (Compose Material 3, 仿 SukiSU 风格)
│       ├── MainActivity.kt
│       ├── AppRoot.kt             3 tab 底部导航
│       ├── HomeScreen.kt          大状态卡 + 2x2 信息网格 + 模式选择
│       ├── AppsScreen.kt          应用画像列表(卡片化) + 黑名单
│       ├── SettingsScreen.kt      接管行为 + 权限 + 性能 + STRICT
│       └── Theme.kt               Material You dynamic color
└── res/
```

## License

GPL-3.0 (因参考了 GPL-3.0 的 Scene/vtools 与 SukiSU-Ultra)
