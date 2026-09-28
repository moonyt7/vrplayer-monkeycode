#!/bin/sh
# Android Studio 打开本仓库后会补齐 gradle/wrapper/gradle-wrapper.jar。
# 若已有 wrapper jar，可直接 ./gradlew :app:assembleDebug
DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec java -jar "$DIR/gradle/wrapper/gradle-wrapper.jar" "$@"
