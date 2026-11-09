#!/usr/bin/env bash
# Daily automatic release for ToneUp Word.
#
# Runs in the Linux build sandbox (not on the maintainer's PC):
#   1. pulls origin/main;
#   2. skips when no app/build code changed since the last published Release;
#   3. bumps versionName/versionCode (patch+1, 1.0.9 -> 1.1.0, 1.9.9 -> 2.0.0);
#   4. runs unit tests and builds the signed release APK;
#   5. writes dist/ToneUpWord.apk + dist/version.json (fixed names);
#   6. commits the version bump, pushes main + tag, publishes the GitHub Release.
#
# Contract: docs/UPDATE_PROTOCOL.md. Secrets live in .git/ci-secrets (never committed).
#
# Exit codes: 0 = released or nothing to do; 1 = failed before publishing.

set -euo pipefail

REPO="hbt123-123/ToneUp_word"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SECRETS_DIR="$ROOT/.git/ci-secrets"
KEYSTORE="$SECRETS_DIR/toneup-release.jks"
KEYINFO="$SECRETS_DIR/key.txt"
WORKTREE="$ROOT/.git/ci-worktree"
CODE_PATHS=(app gradle build.gradle.kts settings.gradle.kts gradle.properties)

log()  { printf '[ci-release %s] %s\n' "$(date '+%F %T')" "$*"; }
fail() { log "ERROR: $*"; exit 1; }

# ---------------------------------------------------------------- toolchain ---
PERSIST="$ROOT/.ci-cache"
if [ -d "$PERSIST/ci-jdk" ]; then
  export JAVA_HOME="${JAVA_HOME:-$PERSIST/ci-jdk}"
else
  # Robolectric's API 36 runtime needs Java 21+, so a plain JDK 17 install is not enough.
  for candidate in /root/.local/share/mise/installs/java/21* /root/.local/share/mise/installs/java/25*; do
    if [ -d "$candidate" ]; then
      export JAVA_HOME="${JAVA_HOME:-$candidate}"
      break
    fi
  done
fi
export PATH="$JAVA_HOME/bin:$PATH"

if [ -d "$PERSIST/ci-android-sdk" ]; then
  default_sdk="$PERSIST/ci-android-sdk"
else
  default_sdk="/opt/android-sdk"
fi
export ANDROID_HOME="${ANDROID_HOME:-$default_sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

if [ -d "$PERSIST/ci-gradle-home" ]; then
  default_gradle="$PERSIST/ci-gradle-home"
else
  default_gradle="$HOME/.gradle"
fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$default_gradle}"
mkdir -p "$GRADLE_USER_HOME"

# Java ignores HTTP_PROXY; translate it for Gradle, sdkmanager and test workers.
if [ -n "${HTTPS_PROXY:-${https_proxy:-}}" ]; then
  proxy="${HTTPS_PROXY:-${https_proxy:-}}"
  proxy="${proxy#*://}"
  proxy_host="${proxy%%:*}"
  proxy_port="${proxy#*:}"
  proxy_port="${proxy_port%%/*}"
  proxy_opts="-Dhttp.proxyHost=$proxy_host -Dhttp.proxyPort=$proxy_port"
  proxy_opts="$proxy_opts -Dhttps.proxyHost=$proxy_host -Dhttps.proxyPort=$proxy_port"
  proxy_opts="$proxy_opts -Dhttp.nonProxyHosts=localhost|127.0.0.1"
  # Robolectric downloads android-all jars itself; point it at the mirror too.
  proxy_opts="$proxy_opts -Drobolectric.dependency.repo.url=https://maven.aliyun.com/repository/public"
  export JAVA_TOOL_OPTIONS="$proxy_opts"
  export GRADLE_OPTS="$proxy_opts"
fi

if [ ! -d "$ANDROID_HOME/platforms/android-36" ]; then
  log "Android SDK missing; installing platform 36 / build-tools 36"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  curl -fsSL -o /tmp/cmdline-tools.zip \
    "https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
  unzip -q -o /tmp/cmdline-tools.zip -d "$ANDROID_HOME/cmdline-tools"
  if [ -d "$ANDROID_HOME/cmdline-tools/cmdline-tools" ]; then
    rm -rf "$ANDROID_HOME/cmdline-tools/latest"
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  fi
  yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
    --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
  "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" \
    "platform-tools" "platforms;android-36" "build-tools;36.0.0"
fi

# Shared Maven Central egress gets HTTP 429 in this sandbox. Resolve through the
# Aliyun mirror via a user-level init script; the repository stays untouched.
INIT_SCRIPT="$GRADLE_USER_HOME/init.d/aliyun-mirror.gradle.kts"
if [ ! -f "$INIT_SCRIPT" ]; then
  mkdir -p "$(dirname "$INIT_SCRIPT")"
  cat > "$INIT_SCRIPT" <<'KTS'
settingsEvaluated {
    pluginManagement.repositories.apply {
        clear()
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
    }
    dependencyResolutionManagement.repositories.apply {
        clear()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}
KTS
fi

# Robolectric's API 36 runtime patches java.io.FileDescriptor through
# jdk.internal.access, which is not exported to the unnamed module on Java 21+.
ROBO_INIT_SCRIPT="$GRADLE_USER_HOME/init.d/robolectric-jvmargs.gradle.kts"
if [ ! -f "$ROBO_INIT_SCRIPT" ]; then
  cat > "$ROBO_INIT_SCRIPT" <<'KTS'
allprojects {
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        jvmArgs(
            "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED",
        )
    }
}
KTS
fi

# ------------------------------------------------------------------ secrets ---
# Preferred CI form: ANDROID_KEYSTORE_B64 + ANDROID_KEY_ALIAS/_PASSWORD.
# Fallback: a local .git/ci-secrets directory or a zipped upload.
if [ ! -f "$KEYSTORE" ] && [ -n "${ANDROID_KEYSTORE_B64:-}" ]; then
  mkdir -p "$SECRETS_DIR"
  printf '%s' "$ANDROID_KEYSTORE_B64" | base64 -d > "$KEYSTORE" \
    || fail "ANDROID_KEYSTORE_B64 is not valid base64"
fi
if [ ! -f "$KEYSTORE" ]; then
  zip_file="$(ls -t "$ROOT"/.uploads/*toneup-release.jks.zip 2>/dev/null | head -1 || true)"
  [ -n "$zip_file" ] || fail "keystore not found in $SECRETS_DIR, .uploads or ANDROID_KEYSTORE_B64"
  mkdir -p "$SECRETS_DIR"
  unzip -q -o "$zip_file" -d "$SECRETS_DIR"
  if [ -f "$SECRETS_DIR/toneup-release.jks/toneup-release.jks" ]; then
    mv "$SECRETS_DIR/toneup-release.jks/toneup-release.jks" "$KEYSTORE"
    mv "$SECRETS_DIR/toneup-release.jks/key.txt" "$KEYINFO"
    rmdir "$SECRETS_DIR/toneup-release.jks" 2>/dev/null || true
  fi
fi
chmod 600 "$KEYSTORE" "$KEYINFO" 2>/dev/null || true

ALIAS="${ANDROID_KEY_ALIAS:-}"
PASSWORD="${ANDROID_KEYSTORE_PASSWORD:-}"
if [ -z "$ALIAS" ] && [ -f "$KEYINFO" ]; then
  ALIAS="$(grep -oE '别名（alias）.*' "$KEYINFO" | grep -oE '`[^`]+`' | head -1 | tr -d '`' || true)"
fi
if [ -z "$PASSWORD" ] && [ -f "$KEYINFO" ]; then
  PASSWORD="$(grep -oE '密钥库口令.*' "$KEYINFO" | grep -oE '`[^`]+`' | head -1 | tr -d '`' || true)"
fi
KEY_PASSWORD="${ANDROID_KEY_PASSWORD:-$PASSWORD}"
[ -n "$ALIAS" ] || fail "cannot read key alias (ANDROID_KEY_ALIAS or key.txt)"
[ -n "$PASSWORD" ] || fail "cannot read keystore password (ANDROID_KEYSTORE_PASSWORD or key.txt)"
export TONEUP_KEYSTORE_FILE="$KEYSTORE"
export TONEUP_KEYSTORE_PASSWORD="$PASSWORD"
export TONEUP_KEY_ALIAS="$ALIAS"
export TONEUP_KEY_PASSWORD="$KEY_PASSWORD"

# --------------------------------------------------------------- git + gh ---
if [ -z "${GITHUB_TOKEN:-}" ]; then
  GITHUB_TOKEN="${GH_TOKEN:-$(printf 'protocol=https\nhost=github.com\n\n' | git credential fill 2>/dev/null \
    | sed -n 's/^password=//p' | head -1)}"
fi
[ -n "$GITHUB_TOKEN" ] || fail "no GitHub credential available for push/release"
export GH_TOKEN="$GITHUB_TOKEN"

export GIT_AUTHOR_NAME="ToneUp Release Bot"
export GIT_AUTHOR_EMAIL="release-bot@users.noreply.github.com"
export GIT_COMMITTER_NAME="$GIT_AUTHOR_NAME"
export GIT_COMMITTER_EMAIL="$GIT_AUTHOR_EMAIL"

git -C "$ROOT" fetch --prune origin main --tags

# Work in a detached worktree so the maintainer's checked-out branch is untouched.
git -C "$ROOT" worktree remove --force "$WORKTREE" >/dev/null 2>&1 || true
git -C "$ROOT" worktree prune
git -C "$ROOT" worktree add -f -B ci-release "$WORKTREE" origin/main
cd "$WORKTREE"
echo "sdk.dir=$ANDROID_HOME" > local.properties

# The sandbox RAM budget varies; allow the caller to cap the daemons.
GRADLE_JVMARGS="${CI_GRADLE_JVMARGS:--Xmx3g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8}"
KOTLIN_DAEMON_JVMARGS="${CI_KOTLIN_DAEMON_JVMARGS:--Xmx1024m}"
sed -i -E "s|^org.gradle.jvmargs=.*|org.gradle.jvmargs=$GRADLE_JVMARGS|" gradle.properties
sed -i -E "s|^kotlin.daemon.jvmargs=.*|kotlin.daemon.jvmargs=$KOTLIN_DAEMON_JVMARGS|" gradle.properties

# ------------------------------------------------------- last published tag ---
release_tags="$(gh release list --repo "$REPO" --limit 50 \
  --json tagName,isDraft,isPrerelease 2>/dev/null \
  | jq -r '.[] | select(.isDraft|not) | select(.isPrerelease|not) | .tagName' \
  | grep -E '^v[0-9]' | sort -V || true)"
LAST_TAG="$(printf '%s\n' "$release_tags" | tail -1)"
if [ -z "$LAST_TAG" ]; then
  LAST_TAG="$(git tag -l 'v*' --sort=-v:refname | head -1)"
fi
[ -n "$LAST_TAG" ] || fail "no previous release tag found"
log "last published release: $LAST_TAG"

NAME="$(sed -n -E 's/^[[:space:]]*versionName = "([^"]+)".*/\1/p' app/build.gradle.kts | head -1)"
CODE="$(sed -n -E 's/^[[:space:]]*versionCode = ([0-9]+).*/\1/p' app/build.gradle.kts | head -1)"
[ -n "$NAME" ] && [ -n "$CODE" ] || fail "cannot read versionName/versionCode"
CUR_TAG="v$NAME"
HEAD_SHA="$(git rev-parse HEAD)"

# Already published and HEAD is exactly that tag: nothing to do.
if git rev-parse "$CUR_TAG" >/dev/null 2>&1 \
   && [ "$(git rev-parse "$CUR_TAG^{commit}")" = "$HEAD_SHA" ] \
   && gh release view "$CUR_TAG" --repo "$REPO" >/dev/null 2>&1; then
  log "$CUR_TAG is already published at HEAD; nothing to do"
  exit 0
fi

# Only app/build inputs justify a new APK; docs-only changes are ignored.
changed="$(git diff --name-only "$LAST_TAG"..HEAD -- "${CODE_PATHS[@]}" || true)"
if [ -z "$changed" ]; then
  log "no app/build changes since $LAST_TAG; nothing to do"
  exit 0
fi

# A failed earlier attempt may have already committed the bump without a Release.
if gh release view "$CUR_TAG" --repo "$REPO" >/dev/null 2>&1; then
  major="${NAME%%.*}"
  rest="${NAME#*.}"
  minor="${rest%%.*}"
  patch="${rest#*.}"
  patch=$((patch + 1))
  if [ "$patch" -gt 9 ]; then
    patch=0
    minor=$((minor + 1))
  fi
  if [ "$minor" -gt 9 ]; then
    minor=0
    major=$((major + 1))
  fi
  NAME="$major.$minor.$patch"
  CODE=$((major * 100 + minor * 10 + patch))
  CUR_TAG="v$NAME"
  sed -i -E "s/^([[:space:]]*versionCode = )[0-9]+/\1$CODE/" app/build.gradle.kts
  sed -i -E "s/^([[:space:]]*versionName = )\"[^\"]+\"/\1\"$NAME\"/" app/build.gradle.kts
  log "bumped to $NAME ($CODE)"
else
  log "publishing pending version $NAME ($CODE) without another bump"
fi

# --------------------------------------------------------------- changelog ---
mkdir -p dist
notes_file="dist/release-notes.md"
{
  echo "## $CUR_TAG"
  echo
  declare -a feat=() fix=() opt=() build=() other=()
  while IFS= read -r subject; do
    case "$subject" in
      "feat:"*)     feat+=("${subject#feat: }") ;;
      "fix:"*)      fix+=("${subject#fix: }") ;;
      "refactor:"*|"perf:"*) opt+=("${subject#*: }") ;;
      "build:"*|"ci:"*|"chore:"*) build+=("${subject#*: }") ;;
      "docs:"*)     other+=("${subject#docs: }") ;;
      *)            other+=("$subject") ;;
    esac
  done < <(git log --no-merges --format='%s' "$LAST_TAG"..HEAD -- "${CODE_PATHS[@]}")
  for pair in "新增|feat" "修复|fix" "优化|opt" "构建|build" "其他|other"; do
    title="${pair%%|*}"
    var="${pair##*|}"
    eval "items=(\"\${${var}[@]}\")"
    if [ "${#items[@]}" -gt 0 ]; then
      echo "### $title"
      echo
      for item in "${items[@]}"; do
        echo "- $item"
      done
      echo
    fi
  done
  echo "完整对比：https://github.com/$REPO/compare/$LAST_TAG...$CUR_TAG"
} > "$notes_file"

# ------------------------------------------------------------------- build ---
log "running unit tests"
bash ./gradlew :app:testDebugUnitTest --console=plain

log "building signed release APK"
bash ./gradlew :app:assembleRelease -x lintVitalRelease --console=plain

APK="app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || fail "release APK missing (unsigned build?)"
APKSIGNER="$ANDROID_HOME/build-tools/36.0.0/apksigner"
EXPECTED_CERT="$(sed -n 's/.*SHA-256：`\([0-9a-f]*\)`.*/\1/p' "$KEYINFO" | head -1)"
"$APKSIGNER" verify --print-certs "$APK" > /tmp/toneup-apk-certs.txt
if [ -n "$EXPECTED_CERT" ] && ! grep -qi "$EXPECTED_CERT" /tmp/toneup-apk-certs.txt; then
  fail "APK certificate does not match the expected release key"
fi

cp "$APK" dist/ToneUpWord.apk
SHA256="$(sha256sum dist/ToneUpWord.apk | awk '{print $1}')"
SIZE="$(stat -c%s dist/ToneUpWord.apk)"
python3 - "$NAME" "$CODE" "$SHA256" "$SIZE" "$notes_file" "dist/version.json" <<'PY'
import json
import sys

name, code, sha, size, notes_file, out = sys.argv[1:7]
notes = open(notes_file, encoding="utf-8").read().strip()
data = {
    "version": name,
    "versionCode": int(code),
    "url": "https://github.com/hbt123-123/ToneUp_word/releases/latest/download/ToneUpWord.apk",
    "sha256": sha,
    "size": int(size),
    "notes": notes,
}
with open(out, "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
PY

# ------------------------------------------------------------ commit/publish ---
if [ "${CI_RELEASE_DRY_RUN:-0}" = "1" ]; then
  log "dry run: stopping before commit/push/release (artifacts in $WORKTREE/dist)"
  exit 0
fi

if ! git diff --quiet -- app/build.gradle.kts; then
  git add app/build.gradle.kts
  git commit -m "release: $CUR_TAG"
fi

git push origin HEAD:main || fail "push to main rejected (origin moved?); will retry next run"

if git rev-parse "$CUR_TAG" >/dev/null 2>&1; then
  [ "$(git rev-parse "$CUR_TAG^{commit}")" = "$(git rev-parse HEAD)" ] \
    || fail "existing tag $CUR_TAG points to a different commit"
else
  git tag "$CUR_TAG"
fi
git push origin "$CUR_TAG"

if gh release view "$CUR_TAG" --repo "$REPO" >/dev/null 2>&1; then
  log "release $CUR_TAG already exists"
else
  gh release create "$CUR_TAG" dist/ToneUpWord.apk dist/version.json \
    --repo "$REPO" --title "$CUR_TAG" --notes-file "$notes_file" --latest --verify-tag
fi

log "published $CUR_TAG (versionCode $CODE, sha256 $SHA256, $SIZE bytes)"
