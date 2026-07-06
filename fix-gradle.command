#!/bin/zsh
set -euo pipefail

PROJECT_DIR="/Users/football/Documents/javaworkbase/pdhbar"
GRADLE_PROPERTIES="$PROJECT_DIR/gradle.properties"
DAEMON_JVM_PROPERTIES="$PROJECT_DIR/gradle/gradle-daemon-jvm.properties"
JDK_HOME="/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home"
JDK_VERSION="26"

cd "$PROJECT_DIR"

echo "==> Project: $PROJECT_DIR"

if [[ ! -x "$JDK_HOME/bin/java" ]]; then
  echo "ERROR: JDK not found at: $JDK_HOME"
  echo "Install JDK $JDK_VERSION or update JDK_HOME in this script."
  exit 1
fi

if [[ ! -x "$JDK_HOME/bin/jlink" ]]; then
  echo "ERROR: jlink not found at: $JDK_HOME/bin/jlink"
  echo "This path is not a full JDK."
  exit 1
fi

echo "==> Using JDK: $JDK_HOME"
"$JDK_HOME/bin/java" -version

set_property() {
  local file="$1"
  local key="$2"
  local value="$3"

  touch "$file"
  if grep -q "^${key}=" "$file"; then
    /usr/bin/sed -i '' "s|^${key}=.*|${key}=${value}|" "$file"
  else
    printf '\n%s=%s\n' "$key" "$value" >> "$file"
  fi
}

echo "==> Fixing gradle.properties"
set_property "$GRADLE_PROPERTIES" "org.gradle.java.home" "$JDK_HOME"
set_property "$GRADLE_PROPERTIES" "org.gradle.java.installations.paths" "$JDK_HOME"

if [[ -f "$DAEMON_JVM_PROPERTIES" ]]; then
  echo "==> Fixing gradle-daemon-jvm.properties"
  set_property "$DAEMON_JVM_PROPERTIES" "toolchainVersion" "$JDK_VERSION"
else
  echo "==> gradle-daemon-jvm.properties not found, skipping"
fi

echo "==> Stopping old Gradle daemons"
./gradlew --stop || true

echo "==> Checking Gradle JVM"
./gradlew --version

echo "==> Done. If VS Code still shows the old error, restart VS Code or run: Java: Clean Java Language Server Workspace"
