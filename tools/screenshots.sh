#!/bin/sh -e
# tools/screenshots.sh [DIR]    render the README's screenshots into DIR
#                               (docs/screenshots by default)
# tools/screenshots.sh --check  render them afresh and compare them with
#                               docs/screenshots, failing on any difference
#
# The pictures are the apps' real Compose screens, drawn on the JVM by
# Robolectric (the ReadmeScreenshots scenes in each app's src/testDebug),
# from PreviewData, with the screens' clock (LocalClock) stopped at
# PreviewData.anchor. shotbox runs the render sealed, with a HOME, locale
# and time zone of its own, so nothing of the machine it runs on shows in
# them, and compares them with the committed ones.
# Needs shotbox (pip install shotbox) and bubblewrap.
cd "$(git rev-parse --show-toplevel)"

ref=docs/screenshots
if [ "${1-}" = --check ]; then
  check=1
  dir=build/screenshots
else
  check=
  dir=${1:-$ref}
fi
mkdir -p "$dir"
rm -f "$dir"/*.png

# Gradle keeps its caches in the usual place, and the toolchain and any
# proxy come through; everything else in the environment is the session's.
shotbox exec \
  --env GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}" \
  --pass JAVA_HOME --pass ANDROID_HOME --pass ANDROID_SDK_ROOT --pass JAVA_TOOL_OPTIONS \
  --pass HTTPS_PROXY --pass HTTP_PROXY --pass NO_PROXY \
  --pass https_proxy --pass http_proxy --pass no_proxy \
  -- ./gradlew --quiet \
    :mobile:testDebugUnitTest --tests '*.screenshots.ReadmeScreenshots' \
    :wear:testDebugUnitTest --tests '*.screenshots.ReadmeScreenshots' \
    -Pscreenshots="$dir"

if [ -n "$check" ]; then
  shotbox compare "$ref" "$dir" --diff "$dir/diff"
fi
