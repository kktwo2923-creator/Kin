<div align="center">

# Kin · 接管助手

**OnePlus V Pro**

Kin 调度守护的图形控制端 —— 智能调度 · 应用画像 · 线程分配 · 实时监控

简体中文 | [English](README_EN.md)

[![License](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](#协议)
[![Platform](https://img.shields.io/badge/Platform-Android%2013%2B-3DDC84?logo=android&logoColor=white)](#)
[![Language](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)](#)
[![Daemon](https://img.shields.io/badge/Daemon-Kin-orange)](https://github.com/kktwo2923-creator/Kin-Daemon)

</div>

---

## 这是什么

**Kin 接管助手** 是 [Kin 调度守护](https://github.com/kktwo2923-creator/Kin-Daemon) 的图形控制端,二者分工明确:

- **Kin 守护**(Magisk/KernelSU 模块)负责真正的 CPU/GPU 调频、governor 参数、线程绑核与实时调度;
- **本 App** 负责配置与感知 —— 应用画像、智能策略、线程规则的编辑下发,以及无障碍前台推送(精准识别前台应用,含小窗/分屏)。

两者通过 `/sdcard/Android/Kin/` 下的配置文件对接(config.json 热重载、mode.txt、applist.prop 等),App 被杀不影响调度持续生效。

## 仓库组成

| 仓库 | 内容 |
|---|---|
| **Kin**(本仓库) | Android 控制端(Jetpack Compose) |
| [**Kin-Daemon**](https://github.com/kktwo2923-creator/Kin-Daemon) | CPU 调度守护(Magisk / KernelSU 模块) |
| 授权/支付后端 | Pro 授权签发、在线支付、更新分发 —— **不对外公开** |

本仓库是纯客户端:授权与更新地址通过构建参数注入(见[编译](#编译)),源码内不含任何私有部署信息。

## 获取与安装

- **App**:官方构建由维护者通过自有渠道分发(含 Pro 授权与在线更新)。
- **守护模块**:见 [Kin-Daemon](https://github.com/kktwo2923-creator/Kin-Daemon),构建与打包方式见其 README。

> ⚠️ **从源码自行构建的版本默认使用占位符后端**:Pro 授权、在线支付与在线更新不可用,其余调度、监控、
> 应用画像等功能完整可用。需要这些能力请自建后端,并用下方构建参数指向它。

## 核心功能

- 🧠 **智能调度(auto)** —— 六状态负载 FSM 按需调频;省电倾向/夜间作息自学习;问答调优(确认-或-纠正学习结果)
- 📊 **智能策略** —— 每状态频率上限可视化自定义;应用分类封顶/关核;游戏调度(风驰调速器 hmbird/scx 切换、FAS 帧感知调频,按应用互斥)
- 🎯 **应用画像** —— 每个 App 绑定独立调度档(风驰游戏/普通游戏/视频/社交/阅读/音乐…),切前台自动生效
- 🧵 **线程分配** —— Metric 式界面:冲突检测、游戏库、应用黑名单;三合一线程规则管理(绑核/实时优先级/实时线程),AppOpt applist.prop 兼容互导
- 🖥️ **悬浮监控** —— 负载/进程/线程/迷你/温度五种悬浮窗;迷你条 Metric 同款样式
- 🔋 **电量记录** —— 充放电曲线、功耗统计、续航估算(守护采样,不依赖 App 前台)
- 🚀 **初始化引导** —— 首次启动引导 ROOT 授权、模块检测下载、无障碍授权、接管方式选择(本 App / Scene 等第三方)
- 🔍 **自检诊断** —— 一键检查 Root/模块/守护/实时状态, 并能生成脱敏的真机能力报告供反馈
- 🎨 **Material You** —— Jetpack Compose + Material 3 动态取色,双排功能页

> **技术栈**:Kotlin + Jetpack Compose · minSdk 33
>
> **致谢**:UI 风格参考 [SukiSU-Ultra](https://github.com/SukiSU-Ultra/SukiSU-Ultra)(GPL-3.0);接管逻辑参考 [helloklf/vtools](https://github.com/helloklf/vtools)(GPL-3.0);线程模板移植自 SutoLiu 的 AppOpt / JZzz 生态(GPL-3.0);智能模式算法移植 [AutoSystemBoost](https://github.com/krilikd/AutoSystemBoost) Smart Mode(MIT);FAS 移植 [fas-rs](https://github.com/shadow3aaa/fas-rs) 控制器;界面与引擎签名对齐 Metric。

## 工作方式

```
无障碍服务 ──前台推送──▶ /sdcard/Android/Kin/topapp.txt ──▶ Kin 守护(inotify 即时消费)
App 配置页 ──读改写──▶ config.json / applist.prop / perapp_powermode.txt ──▶ 守护热重载
守护 ──状态回写──▶ AI/auto_status.txt ──▶ App 实时展示(智能监控/智能策略)
```

- 前台识别以无障碍推送为准(小窗/分屏取面积最大主应用);推送失效时守护自动回退自检,双保险
- 学习与问卷数据存放在 `/sdcard/Android/Kin/AI/`,清 App 数据不丢失

## 编译

本地 Android Studio 打开工程即可(JDK 17);或推送到 GitHub 用 Actions 自动构建。
Release 签名与防重打包配置见 [HARDENING.md](HARDENING.md)。

### 私有部署参数

授权服务器、更新服务器、模块下载地址等**不硬编码在源码里**,默认是占位符。自建部署时通过
构建参数注入(也可写进 `~/.gradle/gradle.properties`,不随仓库分发):

```bash
./gradlew :app:assembleRelease \
  -PctsLicenseBase=https://your-license-server \
  -PctsApiKey=APPVERIFY_你的密钥 \
  -PctsUpdateBase=https://your-update-server \
  -PctsModuleUrl=https://你的下载地址
```

| 参数 | 用途 | 默认值 |
|---|---|---|
| `ctsLicenseBase` | 授权/支付服务器 | `https://license.example.com` |
| `ctsApiKey` | 客户端 API 密钥 | `APPVERIFY_REPLACE_ME` |
| `ctsUpdateBase` | 更新服务器 | `https://update.example.com` |
| `ctsModuleUrl` | 内核模块下载地址 | 本仓库 Releases |

授权服务器若为 HTTP 明文,构建会自动放行 cleartext(HTTPS 则不放行)。

## 协议

GPL-3.0 —— 引用的各上游项目版权归原作者所有,详见源码内署名。
