---
name: subagent-sandbox-confinement
description: Subagents are sandboxed to the project dir and cannot write sibling repos; parent must do cross-repo work
metadata: 
  node_type: memory
  type: project
  originSessionId: 1a773a26-8818-4ca0-87c2-81d3e14bc129
---

In this environment, spawned subagents (Agent tool) are confined to the project working directory `/home/pgenera/wearvian` for ALL file ops (Bash/Read/Write), and the confinement persists even with `dangerouslyDisableSandbox: true` (it's a permission policy, not just the sandbox). The parent session is NOT confined — it can write truly external paths like `/home/pgenera/android-sdk`.

**UPDATE 2026-06-08 (dir reorg):** everything now lives UNDER the working dir — watch repo `watch/`, companion repo `companion/`, decompile `rivian-re/` are all siblings inside `/home/pgenera/wearvian`. So subagents CAN now do cross-repo work (watch↔companion↔decompile) — it's all inside the confinement boundary. The only things still off-limits to subagents are paths truly outside `/home/pgenera/wearvian` (e.g. `/home/pgenera/android-sdk`).

**Why (original 2026-05-30):** a subagent launched to build the companion app when it was a *sibling* of the working dir (`/home/pgenera/wearvian-companion`) was hard-blocked and couldn't even `ls /home/pgenera/`. The reorg moved it inside, removing that block.

**How to apply:** Subagents are fine for anything under `/home/pgenera/wearvian` (now incl. companion + decompile). Only spawn the parent session for writes truly outside that tree.
