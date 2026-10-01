# RustDesk — Android 5.1 / 6.0 Shizuku 操控支持版

> 独立维护分支 · Independently maintained fork, based on [rustdesk/rustdesk](https://github.com/rustdesk/rustdesk) `1.5.0` (AGPL-3.0)

[中文](#中文说明) · [English](#english)

---

## 中文说明

### 这是什么

官方 RustDesk 最低支持 **Android 7**。在 Android 5.1 / 6.0（API 22/23）上，被控端只能共享屏幕、无法被远程操控——官方实现依赖的无障碍手势 API（`dispatchGesture`）在这些系统上不存在，且上游已决定不支持 Android < 7（见 [PR #16391](https://github.com/rustdesk/rustdesk/pull/16391)）。

本仓库基于官方 `1.5.0` 源码，为 **Android 5.1 / 6.0** 补齐了被控端操控：通过 [Shizuku](https://github.com/RikkaApps/Shizuku)（免 root，adb 激活）以 shell 权限注入远程指针与按键事件。

> ⚠️ 本仓库为独立维护分支，**不向上游提交 PR**，也**不跟随官方 nightly/每日构建**；仅当官方发布正式版本时，从对应 release tag 合并更新（见[更新策略](#更新策略)）。

### 功能

- **远程指针**：点击、拖动、右键（长按）、滚轮、中键（单击 = HOME、按住 = 最近任务）、BACK
- **远程按键**：Backspace、方向键、Enter、F 键等（经 Shizuku 注入，**不依赖无障碍**）
- **远程打字**（字母/中文）：走系统无障碍——Android 的限制：免 root 下无障碍是唯一能提交任意文本的通道（中文没有键码）
- 屏幕采集、剪贴板同步与官方版本一致

### 设备要求与 Shizuku 下载

| 系统 | Shizuku 版本 | 下载 |
|---|---|---|
| Android 5.1（API 22） | **v3.6.1** | [shizuku-3.6.1.apk（3.05 MB）](https://github.com/RikkaApps/Shizuku/releases/download/v3.6.1/shizuku-3.6.1.r341.009208f-release.apk) |
| Android 6.0（API 23） | **v13.2.1**（v3.6.1 与 v4~v12 亦可） | [shizuku-v13.2.1.apk（3.4 MB）](https://github.com/RikkaApps/Shizuku/releases/download/v13.2.1/shizuku-v13.2.1.r958.5f9516b-release.apk) |

本分支的客户端与上表全部版本（v3.6.1 → v13.2.1）的 Shizuku 服务端兼容（已逐一核对服务端源码）。

### 快速上手

1. 安装对应版本的 Shizuku，电脑连接手机后执行激活：

   ```bash
   adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/files/start.sh
   ```

2. 安装本仓库 [Releases](https://github.com/CatzTimes/rustdesk/releases) 中的 APK：`armv7` 对应 32 位手机，`arm64` 对应 64 位手机。
3. 打开 RustDesk → 首次使用会弹出 Shizuku 授权框 → **允许**。
4. 系统设置中开启 RustDesk 无障碍服务（**只影响打字**；指针与按键不依赖它）。
5. 从任意设备连接即可操控。

### APK 获取

- [Releases](https://github.com/CatzTimes/rustdesk/releases)：随官方正式版本更新。首个发布 `v1.5.0-shizuku.1`，基于官方 `1.5.0`。
- [Actions](https://github.com/CatzTimes/rustdesk/actions/workflows/build-android-apk.yml)：可手动触发构建（armv7 + arm64）。
- 注意：本仓库 APK 为 debug 签名，与官方版签名不同——从官方版切换需**卸载重装**（会清除应用内配置）。

### 更新策略

- **不**跟随官方 nightly / 每日构建。
- 官方发布正式版本（如 `1.5.0`、未来的 `1.5.x`）时，从对应 release tag 合并到本分支并发布新包。

### 已知限制

- 中文 / IME 组合输入无法从电脑端直接发送：rustdesk 桌面控制端只转发原始按键，属上游行为（所有受控平台如此）。替代方案：复制文字后在远程会话中 **Ctrl+V**（需开启剪贴板同步）。
- 不开启无障碍时，打字不可用（指针与按键不受影响）。

---

## English

### What this is

Official RustDesk supports Android 7+. On Android 5.1/6.0 (API 22/23) a controlled device can share its screen but cannot be remote-controlled: the only injection path upstream implements (`AccessibilityService#dispatchGesture`) does not exist on those versions, and upstream has decided not to support Android < 7 ([PR #16391](https://github.com/rustdesk/rustdesk/pull/16391)).

This fork adds the missing control on top of the official `1.5.0` source: remote pointer and key-code events are injected as the shell user through [Shizuku](https://github.com/RikkaApps/Shizuku) (no root, adb-activated). Keyboard *text* still goes through the accessibility text path — the only non-root way to commit arbitrary text (CJK has no key codes).

> This fork does **not** submit PRs upstream and does **not** track nightly builds; it is only updated by merging official **release tags**.

### Requirements & Shizuku downloads

| Android | Shizuku | Download |
|---|---|---|
| 5.1 (API 22) | **v3.6.1** | [shizuku-3.6.1.apk (3.05 MB)](https://github.com/RikkaApps/Shizuku/releases/download/v3.6.1/shizuku-3.6.1.r341.009208f-release.apk) |
| 6.0 (API 23) | **v13.2.1** (v3.6.1 and v4~v12 also work) | [shizuku-v13.2.1.apk (3.4 MB)](https://github.com/RikkaApps/Shizuku/releases/download/v13.2.1/shizuku-v13.2.1.r958.5f9516b-release.apk) |

The client in this fork is wire-compatible with every server version above (verified against the server sources).

### Quick start

1. Install the matching Shizuku APK and activate it:

   ```bash
   adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/files/start.sh
   ```

2. Install an APK from [Releases](https://github.com/CatzTimes/rustdesk/releases): `armv7` for 32-bit phones, `arm64` for 64-bit phones.
3. Open RustDesk → allow the Shizuku authorization prompt.
4. Enable the RustDesk accessibility service (only needed for typing).
5. Connect from any client.

### Known limitations

- IME-composed text (e.g. Chinese) is not transmitted by the desktop controller (raw keys only) — upstream behavior on all platforms. Workaround: copy the text and **Ctrl+V** in the remote session.
- Typing requires the accessibility toggle; pointer and control keys work without it.

### Credits

Based on [RustDesk](https://github.com/rustdesk/rustdesk) (AGPL-3.0). Injection backend built on [Shizuku](https://github.com/RikkaApps/Shizuku) by RikkaApps.
