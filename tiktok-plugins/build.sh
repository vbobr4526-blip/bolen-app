#!/usr/bin/env bash
# Сборка плагина TikTokMod в .mtp без инструментов мода (margyt).
# Нужны JDK (8+), curl и zip. android.jar и dx скачиваются с Maven Central в tools/.
# Использование: ./build.sh screen-time
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
plugin="$here/${1:?укажите папку плагина, например: ./build.sh screen-time}"
tools="$here/tools"
mkdir -p "$tools"

fetch() { [ -f "$tools/$2" ] || curl -fsSL -o "$tools/$2" "https://repo1.maven.org/maven2/$1"; }
fetch com/google/android/android/4.1.1.4/android-4.1.1.4.jar android.jar
fetch com/jakewharton/android/repackaged/dalvik-dx/14.0.0_r21/dalvik-dx-14.0.0_r21.jar dx.jar

build="$(mktemp -d)"
trap 'rm -rf "$build"' EXIT

# Заглушка API — только для компиляции, в dex не попадает.
mkdir -p "$build/stub" "$build/classes"
javac -nowarn --release 8 -d "$build/stub" -cp "$tools/android.jar" \
    $(find "$here/api-stub/java" -name '*.java')
javac -nowarn --release 8 -encoding UTF-8 -d "$build/classes" \
    -cp "$tools/android.jar:$build/stub" $(find "$plugin/java" -name '*.java')

java -cp "$tools/dx.jar" com.android.dx.command.Main --dex \
    --output="$build/classes.dex" "$build/classes"

id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$plugin/manifest.json")"
cp "$plugin/manifest.json" "$build/"
[ -f "$plugin/icon.png" ] && cp "$plugin/icon.png" "$build/"
rm -f "$plugin/$id.mtp"
(cd "$build" && zip -q -X "$plugin/$id.mtp" manifest.json classes.dex $( [ -f icon.png ] && echo icon.png ))
echo "Готово: $plugin/$id.mtp"
