# VR 播放器

面向手机 VR 盒子的 Android 原生播放器。横屏双目分屏、镜片反向畸变校正，支持 360 / 180 / 180 左右 3D / 立方体 / 立体左右 / 立体上下 / 平面。视角 = 陀螺仪 + 单指拖曳，可锁定。播放页常亮，控件 3 秒无操作隐藏。可打开本机视频或局域网 SMB 共享并支持流媒体播放。

## 本机编译

1. 用 Android Studio 打开仓库根目录
2. 等待 Gradle 同步
3. 选一台 Android 8.0+ 手机，点 Run

命令行：

```bash
# 若还没有 wrapper，先在 Android Studio 里 Generate Gradle Wrapper
./gradlew :app:assembleDebug
```

安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 使用

1. 打开本地视频：系统文件选择器挑 MP4 / MKV
2. 局域网共享：填主机、共享名、账号密码，点进视频文件
3. 把手机横放入 VR 盒子，点一下屏幕调出控件
4. 投影模式不对就在顶部下拉框改
5. 固定视角：画面停在当前朝向，再点一次解锁
6. 分屏按钮可切回单画面（不戴盒子时用）
7. 瞳距滑条 50–80mm，默认 64mm，下次打开沿用

## 投影对照

| 菜单 | 适用片源 |
|------|----------|
| 360 全景 | 普通 equirectangular 360 |
| 180 半景 | 单目 180 VR |
| 180 左右 3D | VR180 SBS，左右眼拼一张 |
| 180 上下 3D | VR180 TB，上下眼拼一张 |
| 立方体 3x2 | YouTube 3x2 cubemap |
| 立体左右 360 | 360 SBS |
| 立体上下 360 | 360 TB |
| 平面 2D | 普通电影 |

## 要求

- Android 8.0（API 26）及以上
- OpenGL ES 2.0
- 陀螺仪可选；没有时仍可拖曳转视角
- SMB 走局域网，手机与 NAS/PC 同一网段

## 工程位置

- 启动页：`app/src/main/java/com/vrplayer/app/HomeActivity.kt`
- 播放页：`app/src/main/java/com/vrplayer/app/PlayerActivity.kt`
- 渲染：`app/src/main/java/com/vrplayer/app/gl/VrRenderer.kt`
- SMB：`app/src/main/java/com/vrplayer/app/smb/`
