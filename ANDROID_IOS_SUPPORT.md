# Android 和 iOS 设备支持说明

## 功能概述

系统已支持通过 USB 连接管理 Android 和 iOS 设备，包括：
- 自动扫描 USB 连接的设备
- 获取设备已安装的应用列表
- 上传并安装应用到设备

## 前置要求

### Android 设备支持

需要安装 **ADB (Android Debug Bridge)**：

**macOS:**
```bash
# 使用 Homebrew 安装
brew install android-platform-tools

# 或下载 Android SDK Platform Tools
# https://developer.android.com/studio/releases/platform-tools
```

**验证安装:**
```bash
adb version
```

**启用 USB 调试:**
1. 在 Android 设备上：设置 → 关于手机 → 连续点击"版本号"7次启用开发者选项
2. 设置 → 开发者选项 → 启用"USB 调试"
3. 连接设备到电脑，在设备上允许 USB 调试授权

### iOS 设备支持

需要安装 **libimobiledevice**：

**macOS:**
```bash
# 使用 Homebrew 安装
brew install libimobiledevice
brew install ideviceinstaller
```

**验证安装:**
```bash
idevice_id -l
ideviceinstaller -h
```

**信任电脑:**
1. 连接 iOS 设备到电脑
2. 在设备上点击"信任此电脑"
3. 输入设备密码

## 功能说明

### 1. 设备自动扫描

系统每 3 秒自动扫描一次 USB 连接的设备：

- **Android**: 使用 `adb devices -l` 命令
- **iOS**: 使用 `idevice_id -l` 命令

扫描到的设备会自动保存到数据库，包括：
- 设备ID（序列号）
- 设备名称
- 平台类型（ANDROID/IOS）
- 系统版本
- 连接状态

### 2. 获取已安装应用

**Android:**
- 使用 `adb shell pm list packages` 获取包名列表
- 使用 `adb shell pm dump` 获取应用详细信息

**iOS:**
- 使用 `ideviceinstaller -l` 获取应用列表

### 3. 安装应用

**Android:**
- 使用 `adb install -r <apk_path>` 安装 APK 文件
- 支持覆盖安装（-r 参数）

**iOS:**
- 使用 `ideviceinstaller -i <ipa_path>` 安装 IPA 文件
- 需要设备已信任电脑

## API 接口

### 获取设备列表
```
GET /api/devices
```

### 按平台获取设备
```
GET /api/devices/platform/{platform}
```
平台值：`ANDROID`, `IOS`, `HARMONYOS`

### 获取设备已安装应用
```
GET /api/applications/platform/{platform}
```

### 上传并安装应用
```
POST /api/applications/upload
Content-Type: multipart/form-data

参数:
- file: 应用文件（.apk 或 .ipa）
- platform: 平台类型（ANDROID 或 IOS）
- deviceId: 设备ID（可选，如果提供会直接安装）
```

## 测试步骤

### 测试 Android 设备

1. **连接设备并验证:**
   ```bash
   adb devices
   ```
   应该看到你的设备列表

2. **启动应用:**
   ```bash
   mvn spring-boot:run
   ```

3. **查看设备列表:**
   ```bash
   curl http://localhost:3000/api/devices/platform/ANDROID
   ```

4. **上传并安装 APK:**
   ```bash
   curl -X POST http://localhost:3000/api/applications/upload \
     -F "file=@your-app.apk" \
     -F "platform=ANDROID" \
     -F "deviceId=your-device-id"
   ```

### 测试 iOS 设备

1. **连接设备并验证:**
   ```bash
   idevice_id -l
   ```
   应该看到你的设备ID

2. **启动应用:**
   ```bash
   mvn spring-boot:run
   ```

3. **查看设备列表:**
   ```bash
   curl http://localhost:3000/api/devices/platform/IOS
   ```

4. **上传并安装 IPA:**
   ```bash
   curl -X POST http://localhost:3000/api/applications/upload \
     -F "file=@your-app.ipa" \
     -F "platform=IOS" \
     -F "deviceId=your-device-id"
   ```

## 常见问题

### Android 设备未检测到

1. 检查 USB 调试是否已启用
2. 检查设备是否已授权（设备上会弹出授权对话框）
3. 尝试运行 `adb kill-server && adb start-server`
4. 检查 USB 线是否支持数据传输（有些线只能充电）

### iOS 设备未检测到

1. 检查是否已安装 libimobiledevice
2. 检查设备是否已信任电脑
3. 尝试运行 `idevicepair pair` 配对设备
4. 检查 USB 线是否支持数据传输

### 应用安装失败

**Android:**
- 检查 APK 文件是否损坏
- 检查设备存储空间
- 检查是否已安装同名应用（可能需要先卸载）

**iOS:**
- 检查 IPA 文件是否有效
- 检查设备是否已信任电脑
- 某些 IPA 可能需要开发者证书签名

## 日志查看

应用启动后，可以在日志中看到：
- 设备扫描结果
- 应用安装进度
- 错误信息

日志级别设置为 DEBUG 时，会显示详细的命令执行信息。
