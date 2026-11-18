#!/bin/sh
# Lets Java/Gradle use the build proxy when Docker passes one in HTTP(S)_PROXY (e.g. Docker Desktop).
# Java ignores these env vars by itself. No-op when no proxy is set (GitHub Actions).
p="${HTTPS_PROXY:-${https_proxy:-${HTTP_PROXY:-${http_proxy:-}}}}"
if [ -n "$p" ]; then
  hp=${p#*://}; hp=${hp%%/*}; hp=${hp##*@}
  host=${hp%:*}; port=${hp##*:}
  [ "$port" = "$hp" ] && port=80
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dhttp.proxyHost=$host -Dhttp.proxyPort=$port -Dhttps.proxyHost=$host -Dhttps.proxyPort=$port"
  export JAVA_TOOL_OPTIONS
fi
exec "$@"
