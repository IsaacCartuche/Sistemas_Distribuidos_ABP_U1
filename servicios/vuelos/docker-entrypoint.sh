#!/bin/sh
set -eu
# JAVA_OPTS se divide en argumentos individuales sin eval ni globbing.
# set -f desactiva comodines; la division por espacios la hace la shell.
set -f
exec java ${JAVA_OPTS:-} -jar /app/app.jar "$@"
