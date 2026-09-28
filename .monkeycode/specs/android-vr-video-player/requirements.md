# Requirements Document

## Introduction

本规格定义一款面向手机 VR 盒子（Cardboard / 头戴支架）的 Android 原生 VR 视频播放器。用户从本机存储或局域网 SMB 共享打开视频后，应用以双目分屏投影渲染画面，支持常见 VR 布局、触摸拖曳与陀螺仪转视角、固定视角、屏幕常亮、播放控件自动隐藏。本版本为单机应用，不包含账号与公网流媒体片库。

## Glossary

- **System**：Android 原生 VR 视频播放器应用
- **User**：在 Android 手机上观看视频的人
- **VR Box**：将手机夹入镜片支架后贴眼观看的头戴设备
- **VR Video**：使用球面、柱面或立体（左右/上下）投影编码的全景或半景视频
- **Projection Mode**：将解码后的视频纹理映射到几何体上的方式，包括 360 等距柱状投影（Equirectangular）、180 等距柱状投影、立方体贴图（Cubemap 3x2）、立体左右（SBS）、立体上下（TB）、平面 2D
- **Split Screen**：将画面分成左右两眼视口，供 VR Box 观看
- **Fixed View**：锁定当前摄像机朝向，忽略陀螺仪与触摸拖曳对视角的继续改变
- **Immersive Playback**：播放控件已隐藏、双目全屏、屏幕保持常亮的观看状态
- **Playback Controls**：播放/暂停、进度条、投影模式、分屏开关、固定视角开关、返回等叠加控件
- **Local Video File**：设备存储中 User 通过系统文件选择器选中的视频文件
- **SMB Share**：局域网内通过 SMB/CIFS 协议共享的文件夹（如 Windows 共享、Samba、NAS）
- **Keep Screen On**：播放期间阻止系统自动息屏

## Requirements

### Requirement 1: 打开本地视频

**User Story:** AS User, I want to pick a local video file and play it, so that I can watch VR content already stored on the phone.

#### Acceptance Criteria

1. WHEN User launches the System, the System SHALL display a start screen with an action to open a local video file.
2. WHEN User selects a local video file that the decoder can open, the System SHALL open the player screen and start playback.
3. IF the selected file cannot be opened or decoded, the System SHALL remain on the current screen and display an error message in Chinese that names the failure reason.
4. WHEN User returns from the player screen, the System SHALL stop playback and release decoder and OpenGL resources.

### Requirement 2: 从局域网 SMB 共享打开文件

**User Story:** AS User, I want to browse a LAN shared folder and play a video from it, so that I can watch files on a NAS or PC without copying them to the phone.

#### Acceptance Criteria

1. WHEN User chooses to open an SMB share, the System SHALL show a form for host, share name, username, password, and optional domain.
2. WHEN User submits valid SMB credentials and the share is reachable, the System SHALL list directories and video files in that share.
3. WHEN User selects a directory in the SMB list, the System SHALL list the contents of that directory.
4. WHEN User selects a video file in the SMB list, the System SHALL stream that file into the player without copying the entire file to internal storage first.
5. IF the SMB host is unreachable, authentication fails, or the file cannot be read, the System SHALL display an error message in Chinese that names the failure reason.
6. WHERE the System lists SMB files, the System SHALL show entries with extensions mp4, mkv, webm, mov, and avi as playable video files.

### Requirement 3: VR 投影格式支持

**User Story:** AS User, I want common VR video layouts to render correctly, so that 360, 180, stereo, and cubemap files look right.

#### Acceptance Criteria

1. WHEN playback starts, the System SHALL map the video texture using Equirectangular 360 as the default projection mode.
2. WHEN User selects a projection mode, the System SHALL apply that mode within 1 second and keep the current playback position.
3. WHERE User opens the projection menu, the System SHALL offer these modes: Equirectangular 360, Equirectangular 180, Stereo Side-by-Side 180, Stereo Top-Bottom 180, Cubemap 3x2, Stereo Side-by-Side 360, Stereo Top-Bottom 360, Flat 2D.
4. WHEN projection mode is Stereo Side-by-Side 360 or Stereo Side-by-Side 180, the System SHALL sample the left half of the frame for the left eye and the right half for the right eye.
5. WHEN projection mode is Stereo Top-Bottom 360 or Stereo Top-Bottom 180, the System SHALL sample the top half of the frame for the left eye and the bottom half for the right eye.
6. WHEN projection mode is Stereo Side-by-Side 180 or Stereo Top-Bottom 180, the System SHALL map each eye onto a 180-degree front hemisphere.

### Requirement 4: 手机 VR 盒子双目分屏

**User Story:** AS User, I want a side-by-side dual-eye view with lens distortion, so that I can drop the phone into a VR box and watch.

#### Acceptance Criteria

1. WHEN the player screen opens, the System SHALL render in landscape Split Screen with left and right eye viewports.
2. WHILE Split Screen is on, the System SHALL apply inverse lens distortion across the full rectangular eye viewport, offset each eye camera by half of the current interpupillary distance, and shift each eye image center horizontally according to that distance.
3. WHEN User turns Split Screen off, the System SHALL render a single full-screen monoscopic view.
4. WHILE the player screen is visible, the System SHALL lock orientation to landscape.
5. WHEN Playback Controls are visible, the System SHALL show an interpupillary distance slider from 50 millimetres to 80 millimetres in 1 millimetre steps, default 64 millimetres.
6. WHEN User changes the interpupillary distance, the System SHALL update the eye camera offset within 200 milliseconds and keep the value after the player screen is closed.

### Requirement 5: 触摸拖曳与陀螺仪转视角

**User Story:** AS User, I want to look around by moving the phone or dragging a finger, so that I can inspect a 360 scene.

#### Acceptance Criteria

1. WHILE a VR projection mode is active and Fixed View is off, the System SHALL rotate the view according to the device gyroscope or rotation vector sensor.
2. WHILE a VR projection mode is active and Fixed View is off, the System SHALL add yaw and pitch from a one-finger drag, with pitch limited to the range -89 degrees to 89 degrees.
3. WHEN both gyroscope and drag are in use, the System SHALL combine them so that drag applies as an offset on top of the sensor orientation.
4. IF the device has no gyroscope, the System SHALL keep drag rotation available and ignore the missing sensor.

### Requirement 6: 固定视角

**User Story:** AS User, I want to lock the current view direction, so that the picture stays still inside the VR box.

#### Acceptance Criteria

1. WHEN User turns on Fixed View, the System SHALL keep the current yaw and pitch and ignore subsequent gyroscope samples and drag gestures for view rotation.
2. WHEN User turns off Fixed View, the System SHALL resume gyroscope and drag rotation from the locked yaw and pitch.
3. WHILE Fixed View is on and Playback Controls are visible, the System SHALL show an indicator that Fixed View is active.
4. WHEN playback starts, the System SHALL leave Fixed View off.

### Requirement 7: 屏幕常亮

**User Story:** AS User, I want the screen to stay on during playback, so that a long VR video does not dim inside the headset.

#### Acceptance Criteria

1. WHILE the player screen is visible, the System SHALL keep the screen on whether playback is running or paused.
2. WHEN User leaves the player screen, the System SHALL restore the device default idle timeout behavior.
3. IF the player screen moves to the background, the System SHALL pause playback and allow the device idle timeout to operate.

### Requirement 8: 播放控件自动隐藏

**User Story:** AS User, I want playback controls to hide by themselves, so that the VR picture is unobstructed while I watch in a VR box.

#### Acceptance Criteria

1. WHEN playback is running and User has not touched Playback Controls for 3 seconds, the System SHALL hide Playback Controls.
2. WHEN Playback Controls are hidden and User taps the player surface, the System SHALL show Playback Controls.
3. WHEN User interacts with Playback Controls, the System SHALL restart the 3-second hide timer.
4. WHILE playback is paused, the System SHALL keep Playback Controls visible.
5. WHILE Playback Controls are hidden, the System SHALL hide the system status bar and navigation bar.

### Requirement 9: 基础播放控制

**User Story:** AS User, I want play, pause, and seek, so that I can control a video the same way as a normal player.

#### Acceptance Criteria

1. WHEN User taps play or pause, the System SHALL toggle between playing and paused within 200 milliseconds.
2. WHEN User drags the seek bar to a new position, the System SHALL seek to that position and continue in the previous play/pause state.
3. WHILE a video is playing, the System SHALL update the elapsed time label at least once per second.
4. WHEN playback reaches the end of the file, the System SHALL pause on the last frame and show Playback Controls.

### Requirement 10: 设备与文件范围

**User Story:** AS User, I want the player to run on a typical Android phone, so that I can use it with a VR box without extra compute hardware.

#### Acceptance Criteria

1. WHERE the device runs Android 8.0 (API 26) or higher, the System SHALL install and launch as a standalone phone application.
2. WHERE the System plays a video, the System SHALL accept MP4 and MKV containers with H.264 or H.265 video when the device decoder supports that codec.
3. WHILE the player screen is in Split Screen, the System SHALL present two eye views for a VR Box.
