---
name: companion-build-only-on-changes
description: "only build/ship the companion app when it has meaningful changes; don't lockstep its version with the watch"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: b194ce1f-01a8-4332-84ac-f74059335d3e
---

Only build and upload the companion phone app when it has meaningful changes of its own. Do NOT bump/ship the companion just to keep its version number matching the watch — the version lanes are independent (watch 1xxx, companion 2xxx) and may diverge.

**Why:** A companion build with no functional change wastes a slow R8 build and an upload, and a misleading version bump implies changes that aren't there. The user will explicitly say when they want the watch and companion version numbers to match.

**How to apply:** When a task touches only the watch (`watch/` repo), build/upload only the watch. Leave companion source and version untouched. Revert any reflexive companion version bump. See [[no-build-by-default]] and [[be-sure-before-release-bundle]].
