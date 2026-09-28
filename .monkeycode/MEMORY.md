# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Format

### User Instruction Entry
User instruction entries should follow this format:

[User Instruction Summary]
- Date: [YYYY-MM-DD]
- Context: [Mentioned scenario or time]
- Instructions:
  - [Content of user teaching or instruction, described line by line]

### Project Knowledge Entry
Entries discovered by the Agent during task execution should follow this format:

[Project Knowledge Summary]
- Date: [YYYY-MM-DD]
- Context: Discovered by Agent while performing [specific task description]
- Category: [Operations & Deployment|Build Methods|Testing Methods|Troubleshooting & Debugging|Workflow & Collaboration|Environment Configuration]
- Instructions:
  - [Specific knowledge points, described line by line]

## Deduplication Strategy
- Before adding a new entry, check for similar or identical instructions.
- If a duplicate is found, skip the new entry or merge it with the existing one.
- When merging, update the context or date information.
- This helps avoid redundant entries and keeps the memory file tidy.

## Entries

[Android 构建环境]
- Date: 2026-09-09
- Context: Discovered by Agent while creating the VR player Android project
- Category: Environment Configuration
- Instructions:
  - 当前 Agent 执行环境没有 Android SDK / JDK / Gradle，无法在此编译 APK
  - 用户需用本机 Android Studio 打开仓库根目录同步 Gradle 后真机运行
  - minSdk 26，applicationId 为 com.vrplayer.app
  - 构建入口：`./gradlew :app:assembleDebug`（需先由 Android Studio 生成 wrapper jar）
