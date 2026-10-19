<div align="center">

# Kin · Companion

**OnePlus V Pro**

GUI front-end for the Kin scheduling daemon — smart scheduling · per-app profiles · thread allocation · live monitoring

[简体中文](README.md) | English

[![License](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](#license)
[![Platform](https://img.shields.io/badge/Platform-Android%2013%2B-3DDC84?logo=android&logoColor=white)](#)
[![Language](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)](#)
[![Daemon](https://img.shields.io/badge/Daemon-Kin-orange)](https://github.com/kktwo2923-creator/Kin-Daemon)

</div>

---

## What is this

**Kin Companion** is the graphical control panel for the [Kin scheduling daemon](https://github.com/kktwo2923-creator/Kin-Daemon). The two have a clear division of labor:

- **Kin daemon** (a Magisk/KernelSU module) does the real work — CPU/GPU frequency control, governor parameters, thread affinity and realtime scheduling;
- **This app** handles configuration and sensing — editing per-app profiles, smart policies and thread rules, plus accessibility-based foreground push (accurate foreground detection, including freeform windows / split screen).

They communicate through files under `/sdcard/Android/Kin/` (hot-reloaded `config.json`, `mode.txt`, `applist.prop`, …). Scheduling keeps running even if the app gets killed.

## Repositories

| Repository | Contents |
|---|---|
| **Kin** (this repo) | Android control panel (Jetpack Compose) |
| [**Kin-Daemon**](https://github.com/kktwo2923-creator/Kin-Daemon) | CPU scheduling daemon (Magisk / KernelSU module) |
| Licensing / payment backend | Pro activation, online payment, update delivery — **not public** |

This repo is the client only: server addresses are injected at build time (see [Building](#building)), so the source contains no private deployment details.

## Getting it

- **App**: official builds are distributed by the maintainer through their own channel (with Pro activation and online updates).
- **Daemon module**: see [Kin-Daemon](https://github.com/kktwo2923-creator/Kin-Daemon); build & packaging instructions live in its README.

> ⚠️ **A build made straight from source uses placeholder endpoints**: Pro activation, online payment and update checks will not work, while scheduling, monitoring and per-app profiles are fully functional. To enable them, host your own backend and point the build at it with the parameters below.

## Features

- 🧠 **Smart mode (auto)** — six-state load FSM; self-learned power-saving bias and sleep schedule; questionnaire tuning (confirm-or-correct what has been learned)
- 📊 **Smart policy** — per-state frequency caps with visual editing; per-category caps / core parking; game scheduling (hmbird/scx governor switch for "FengChi games", FAS frame-aware scaling — mutually exclusive per app)
- 🎯 **Per-app profiles** — bind each app to its own scheduling profile (FengChi game / game / video / social / reading / music …), applied automatically on foreground switch
- 🧵 **Thread allocation** — Metric-style UI: conflict detection, game library, app blacklist; 3-in-1 rule manager (affinity rules / realtime priority / live threads), AppOpt `applist.prop` compatible
- 🖥️ **Overlays** — load / process / thread / mini / temperature floating monitors; the mini bar matches Metric's look
- 🔋 **Battery log** — charge/discharge curves, power statistics, runtime estimation (sampled by the daemon, works without the app in foreground)
- 🚀 **Setup wizard** — first-launch guidance for ROOT, module detection & download, accessibility permission, takeover mode (this app / third-party such as Scene)
- 🎨 **Material You** — Jetpack Compose + Material 3 dynamic color, two-column feature hub

> **Stack**: Kotlin + Jetpack Compose · minSdk 33
>
> **Credits**: UI style inspired by [SukiSU-Ultra](https://github.com/SukiSU-Ultra/SukiSU-Ultra) (GPL-3.0); takeover logic references [helloklf/vtools](https://github.com/helloklf/vtools) (GPL-3.0); thread templates ported from SutoLiu's AppOpt / JZzz ecosystem (GPL-3.0); smart-mode algorithm ported from [AutoSystemBoost](https://github.com/krilikd/AutoSystemBoost) Smart Mode (MIT); FAS controller ported from [fas-rs](https://github.com/shadow3aaa/fas-rs); UI & engine signatures aligned with Metric.

## How it works

```
Accessibility service ──foreground push──▶ /sdcard/Android/Kin/topapp.txt ──▶ Kin daemon (inotify)
App settings pages ──read-modify-write──▶ config.json / applist.prop / perapp_powermode.txt ──▶ hot reload
Daemon ──status feedback──▶ AI/auto_status.txt ──▶ live display in the app
```

- Foreground detection prefers the accessibility push (largest app window wins in freeform/split-screen); if the push goes stale the daemon falls back to self-detection — double insurance
- Learning and questionnaire data live in `/sdcard/Android/Kin/AI/` and survive clearing app data

## Building

Open the project in Android Studio (JDK 17), or push to GitHub and let Actions build it.
Release signing / anti-repackage hardening: see [HARDENING.md](HARDENING.md).

### Private deployment parameters

Endpoints and keys are **not hardcoded** — they default to placeholders and are injected at build time
(or put into `~/.gradle/gradle.properties`, which never ships with the repo):

```bash
./gradlew :app:assembleRelease \
  -PctsLicenseBase=https://your-license-server \
  -PctsApiKey=APPVERIFY_your_key \
  -PctsUpdateBase=https://your-update-server \
  -PctsModuleUrl=https://your-download-url
```

| Parameter | Purpose | Default |
|---|---|---|
| `ctsLicenseBase` | Licensing / payment server | `https://license.example.com` |
| `ctsApiKey` | Client API key | `APPVERIFY_REPLACE_ME` |
| `ctsUpdateBase` | Update server | `https://update.example.com` |
| `ctsModuleUrl` | Module download URL | this repo's Releases |

If the licensing server is plain HTTP, the build automatically permits cleartext traffic (HTTPS does not).

## License

GPL-3.0 — upstream projects referenced above remain under their own copyrights; see in-source attributions.
