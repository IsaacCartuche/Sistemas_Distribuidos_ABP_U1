#!/bin/sh
set -eu
# Separación convencional de opciones JVM, SIN eval ni expansión de comodines.
# JAVA_TOOL_OPTIONS también es compatible y lo interpreta la propia JVM.
set -f
exec java ${JAVA_OPTS:-} -jar /app/app.jar "$@"
