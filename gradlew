#!/bin/sh
# Gradle startup script for POSIX shells (Git Bash 등)
APP_HOME=$(cd "$(dirname "$0")" && pwd -P)
if [ -n "$JAVA_HOME" ]; then JAVACMD="$JAVA_HOME/bin/java"; else JAVACMD=java; fi
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
case "$(uname)" in CYGWIN*|MINGW*|MSYS*) CLASSPATH=$(cygpath -w "$CLASSPATH") ;; esac
exec "$JAVACMD" -Xmx64m -Xms64m $JAVA_OPTS $GRADLE_OPTS "-Dorg.gradle.appname=$(basename "$0")" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
