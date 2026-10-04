#!/bin/sh
set -eu

# Let Gradle resolve dependencies/metadata, then exit before Native Image starts.
module=$1
task=$2
main_class=$3
args_dir=$(mktemp -d)
trap 'rm -rf "$args_dir"' EXIT
./gradlew --no-daemon --max-workers=1 \
    "-Dorg.gradle.jvmargs=-Xmx256m -Djava.io.tmpdir=$args_dir" \
    ":$module:$task" --build-args=--dry-run --verbose
set -- "$args_dir"/native-image-*.args
if [ ! -f "$1" ]; then
    echo "Expected a Native Image argument file in $args_dir" >&2
    find /tmp -name "native-image-*.args" >&2
    exit 1
fi
output_dir="/workspace/$module/build/native/$task"
sed '/^--dry-run$/d' "$1" > "$output_dir/native-image.args"
cd "$output_dir"
"$JAVA_HOME/bin/native-image" @native-image.args "-J-Xmx${NATIVE_IMAGE_BUILD_HEAP:-3000m}" "$main_class"
