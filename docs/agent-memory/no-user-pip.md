---
name: no-user-pip
description: "Never pip-install into the user's environment; use a venv or ask for Debian packages"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 1a773a26-8818-4ca0-87c2-81d3e14bc129
---

Never run `pip install` against the user's Python (no `--user`, and never
`--break-system-packages`). It pollutes their global site-packages and they
will ask you to undo it.

**Why:** the user keeps their base environment clean and manages system tooling
via Debian packages.

**How to apply:** if you need a Python lib, create a throwaway venv
(`python3 -m venv /tmp/…venv && /tmp/…venv/bin/pip install …`) inside the
project/tmp, OR tell the user the `apt`/Debian package to install and let them
do it (preferred). Prefer suggesting the Debian package.
