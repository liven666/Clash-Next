# Clash Next — 安卓原生版

基于 [FlClash](https://github.com/chen08209/FlClash) 二次开发的自用客户端，
复用完整的 Mihomo 内核和 Android 服务层，以 Kotlin 原生界面重构日常操作。

当前测试版：**0.1.9-alpha10** · Android arm64 · **33.92 MiB**。

- [下载安装包、查看发布说明](https://github.com/liven666/Clash-Next/releases/tag/android-0.1.9-alpha10)
- [安装与使用 SOP](ANDROID_USER_SOP.md)
- [新版构建说明](CLASH_NEXT_BUILD.md)
- [版本记录与完成情况](V2_STATUS.md)

## 已实现的能力

- 通过 HTTPS 订阅、YAML 文件、相机扫码或相册二维码导入配置。
- 开启/关闭 VPN，切换规则、全局、直连模式，显示实时速度与连接时长。
- 未连接时查看、搜索和预选节点；连接后可测速和切换节点。
- 支持 1x2、1x4、2x2、2x4 桌面组件，按尺寸展示节点、国旗、时长与出口 IP。
- 四份 Geo 资源内置于 APK，安装后无需单独下载。
- 查看连接详情，收集本地滚动日志，支持常见凭据脱敏和日志导出。
- 统一的浅色/深色原生界面，设置页可查看当前版本。
- 新版 APK 不包含统计、崩溃上传、Firebase、ML Kit 或广告。

当前仍是使用开发签名的 **Alpha 测试版**。已完成 Android 15 模拟器验收，
vivo X300 Pro 的组件及后台行为仍需真机复测。订阅流量/到期展示、应用分流编辑器、
完整备份恢复尚未完成；本次不提供 Windows、macOS 或 iOS 新版安装包。

新版入口是 `android/v2app`；`android/app` 与 `lib/` 保留旧 Flutter 客户端源码，
不代表新版 APK 内含 Flutter 界面。配置与日志保存在本地；订阅、提供器更新和出口 IP
检查仍会按功能需要联网。

## 来源与许可

以 FlClash 提交 `7c831855efedceb1a72bd0b4c18da026593d0853` 为基线，
Mihomo 子模块固定为 `80362fc1895dcf60b79b562896653046e0687413`。
保留原始历史、版权声明和 [GPLv3 许可证](LICENSE)，不是 FlClash 官方发行版。
公开仓库不包含个人订阅、签名私钥、本地 SDK、模拟器数据、日志或构建缓存。

## 原 FlClash 说明

以下保留上游说明，适用于旧 Flutter 客户端；其中的下载地址和平台能力不代表 Clash Next 本次发布范围。

<div>

[**English**](README.md)

</div>

## FlClash

[![Downloads](https://img.shields.io/github/downloads/chen08209/FlClash/total?style=flat-square&logo=github)](https://github.com/chen08209/FlClash/releases/)[![Last Version](https://img.shields.io/github/release/chen08209/FlClash/all.svg?style=flat-square)](https://github.com/chen08209/FlClash/releases/)[![License](https://img.shields.io/github/license/chen08209/FlClash?style=flat-square)](LICENSE)

[![Channel](https://img.shields.io/badge/Telegram-Channel-blue?style=flat-square&logo=telegram)](https://t.me/FlClash)

基于ClashMeta的多平台代理客户端，简单易用，开源无广告。

on Desktop:
<p style="text-align: center;">
    <img alt="desktop" src="snapshots/desktop.gif">
</p>

on Mobile:
<p style="text-align: center;">
    <img alt="mobile" src="snapshots/mobile.gif">
</p>

## Features

✈️ 多平台: Android, Windows, macOS and Linux

💻 自适应多个屏幕尺寸,多种颜色主题可供选择

💡 基本 Material You 设计, 类[Surfboard](https://github.com/getsurfboard/surfboard)用户界面

☁️ 支持通过WebDAV同步数据

✨ 支持一键导入订阅, 深色模式

## Use

### Linux

⚠️ 使用前请确保安装以下依赖

   ```bash
    sudo apt-get install libayatana-appindicator3-dev
    sudo apt-get install libkeybinder-3.0-dev
   ```

### Android

支持下列操作

   ```bash
    com.follow.clash.action.START
    
    com.follow.clash.action.STOP
    
    com.follow.clash.action.TOGGLE
   ```

## Download

<a href="https://chen08209.github.io/FlClash-fdroid-repo/repo?fingerprint=789D6D32668712EF7672F9E58DEEB15FBD6DCEEC5AE7A4371EA72F2AAE8A12FD"><img alt="Get it on F-Droid" src="snapshots/get-it-on-fdroid.svg" width="200px"/></a> <a href="https://github.com/chen08209/FlClash/releases"><img alt="Get it on GitHub" src="snapshots/get-it-on-github.svg" width="200px"/></a>

### Homebrew

```bash
brew tap chen08209/tap
brew install --cask flclash
```

## Build

1. 更新 submodules
   ```bash
   git submodule update --init --recursive
   ```

2. 安装 `Flutter` 以及 `Golang` 环境

3. 构建应用

    - android

        1. 安装  `Android SDK` ,  `Android NDK`

        2. 设置 `ANDROID_NDK` 环境变量

        3. 运行构建脚本

           ```bash
           dart setup.dart android
           ```

    - windows

        1. 你需要一个windows客户端

        2. 安装 `GCC`，`Inno Setup`

        3. 运行构建脚本

           ```bash
           dart setup.dart windows
           ```

    - linux

        1. 你需要一个linux客户端

        2. 依赖会由 setup 脚本自动安装，也可以手动安装：
           ```bash
           sudo apt-get install -y libayatana-appindicator3-dev libkeybinder-3.0-dev
           ```

        3. 运行构建脚本

           ```bash
           dart setup.dart linux
           ```

    - macOS

        1. 你需要一个macOS客户端

        2. 运行构建脚本

           ```bash
           dart setup.dart macos
           ```

## Star

支持开发者的最简单方式是点击页面顶部的星标（⭐）。

<p style="text-align: center;">
    <a href="https://api.star-history.com/svg?repos=chen08209/FlClash&Date">
        <img alt="start" width=50% src="https://api.star-history.com/svg?repos=chen08209/FlClash&Date"/>
    </a>
</p>
