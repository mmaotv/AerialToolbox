#!/usr/bin/env sh

##############################################################################
#
#   Gradle startup script for UN*X
#
##############################################################################

# Attempt to set APP_HOME
# Resolve links: $0 may be a link
PRG="$0"
# Need this for relative symlinks.
while [ -h "$PRG" ] ; do
    ls=`ls -ld "$PRG"`
    link=`expr "$ls" : '.*-> \(.*\)$'`
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=`dirname "$PRG"`"/$link"
    fi
done

SAVED="`pwd`"
cd "`dirname \"$PRG\"`/" >/dev/null
APP_HOME="`pwd -P`"
cd "$SAVED" >/dev/null

APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`

# Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
DEFAULT_JVM_OPTS="-Xmx64m -Xms64m"

# Use the maximum available from the user's system memory
# to avoid "Cannot allocate memory" errors
if [ -z "$JAVA_OPTS" ]; then
    JAVA_OPTS="-Xmx256m -Xms128m"
fi

# Escape application args
save () {
    for i do printf %s\\n "$i" | sed "s/'/'\\\\''/g;1s/^/'/;\$s/\$/' \\\\/" ; done
    echo " "
}

# Collect all arguments for the java command, following the shell quoting and substitution rules
eval set -- `save "$@"`

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        # IBM's JDK on AIX uses strange locations for the executables
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
else
    JAVACMD="java"
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation, or add the Java installation directory
to the PATH environment variable."
fi

# Increase the maximum file descriptors if we can
if [ "$( uname -s )" = 'Linux' ] || [ "$( uname -s )" = 'Darwin' ]; then
    MAX_FD="maximum"
    if [ -z "$MAX_FD" ] || [ "$MAX_FD" = "maximum" ] || [ "$MAX_FD" = "max" ]; then
        # Re-direct stderr to stdout, to actually capture the error
        # in the variable
        MAX_FD_LIMIT=`ulimit -H -n 2>/dev/null`
        if [ $? -eq 0 ] && [ "$MAX_FD_LIMIT" != "unlimited" ]; then
            # Re-direct stderr to stdout, to actually capture the error
            # in the variable
            MAX_FD="$MAX_FD_LIMIT"
        else
            # Re-direct stderr to stdout, to actually capture the error
            # in the variable
            MAX_FD="1024"
        fi
    fi

    # Actually increase the file descriptors
    if [ "$MAX_FD" != "1024" ] ; then
        ulimit -n $MAX_FD 2>/dev/null
        # Re-direct stderr to stdout, to actually capture the error
        # in the variable
        if [ $? -ne 0 ] ; then
            # Re-direct stderr to stdout, to actually capture the error
            # in the variable
            echo "Could not set maximum file descriptor limit: $MAX_FD"
        fi
    fi
fi

# Split up the JVM options
JVM_OPTS=""
if [ -n "$JAVA_OPTS" ] ; then
    JVM_OPTS="$JVM_OPTS $JAVA_OPTS"
fi
if [ -n "$GRADLE_OPTS" ] ; then
    JVM_OPTS="$JVM_OPTS $GRADLE_OPTS"
fi

# Attempt to move the default Gradle temp directory under the user's home directory
if [ -z "$GRADLE_OPTS" ] ; then
    GRADLE_OPTS="-Dorg.gradle.apppathing.ignore=true"
else
    GRADLE_OPTS="$GRADLE_OPTS -Dorg.gradle.apppathing.ignore=true"
fi

# Attempt to move the default Gradle temp directory under the user's home directory
if [ -z "$GRADLE_USER_HOME" ] ; then
    GRADLE_USER_HOME="$HOME/.gradle"
fi

# Determine the user's working directory
USER_HOME="$( getent passwd "$USER" | cut -d: -f6 )"
if [ -z "$USER_HOME" ] ; then
    USER_HOME="$HOME"
fi

# Determine the Gradle home directory
GRADLE_HOME=""
if [ -z "$GRADLE_HOME" ] ; then
    # Fallback to default installation location
    if [ -d "/opt/gradle" ] ; then
        GRADLE_HOME="/opt/gradle"
    elif [ -d "/usr/local/gradle" ] ; then
        GRADLE_HOME="/usr/local/gradle"
    elif [ -d "$USER_HOME/.gradle/wrapper/dists" ] ; then
        # Use the Gradle wrapper
        GRADLE_HOME="$USER_HOME/.gradle/wrapper/dists"
    fi
fi

# Run Gradle
exec "$JAVACMD" $JVM_OPTS $GRADLE_OPTS \
    -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain "$@"
