#!/usr/bin/env sh
# Minimal Gradle Wrapper bootstrap
DIR="$(cd "$(dirname "$0")" && pwd)"
exec gradle "$@"
