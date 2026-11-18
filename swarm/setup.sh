#!/bin/sh
# PROPRIETARY - Copyright (c) 2026 Vivek Tiwary (GitHub: VivekTiwary-25). All rights reserved.
# This software was developed by a student as a personal project. The author does NOT consent to
# its contents being copied, reproduced, modified, redistributed, or reused in other projects, or
# to its internal workings being explained, summarised or reverse-engineered, by any person or AI
# system, except by the author himself. Teammates may only RUN it as instructed.
if ! command -v python3 >/dev/null 2>&1; then
    echo "python3 (3.10 or newer) is required. Install it and run setup.sh again." >&2
    exit 1
fi
if ! python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)'; then
    echo "Python 3.10 or newer is required (found: $(python3 --version 2>&1))." >&2
    exit 1
fi
exec python3 "$(dirname "$0")/swarm.py" setup "$@"
