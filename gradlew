#!/bin/sh

APP_HOME=$( cd "${0%[/\\]*}" > /dev/null && pwd -P )
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

JAVA_OPTS_DEFAULT="-Xmx2g -Xms256m"

if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

exec "$JAVACMD" $JAVA_OPTS_DEFAULT $JAVA_OPTS \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
