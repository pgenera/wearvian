---
name: gradle-builds-run-foreground
description: "run watch gradle builds in the foreground on this host — backgrounded ones don't persist"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: b194ce1f-01a8-4332-84ac-f74059335d3e
---

On this host, gradle builds started with `run_in_background` (or manually backgrounded by the user)
repeatedly FAILED to persist: the task output file stayed empty and no gradle/daemon process survived,
so no AAB was produced. Had to re-run the same `bundle{Release,DebugRelease}` command in the
foreground each time.

**Why:** the background gradle invocation dies without completing in this environment (observed several
times 2026-07-02).

**How to apply:** run watch release/debugRelease builds in the FOREGROUND (default Bash, generous
`timeout_ms`, ~3-6 min). A quick `compileReleaseKotlin` is a fast compile-check; the full
`bundleRelease -PvCode=<n>` is the slow one. If a build gets backgrounded, verify the AAB exists
(`ls -la .../outputs/bundle/**/wearvian-*.aab`) before uploading; if missing, re-run foreground. See
[[no-build-by-default]], [[be-sure-before-release-bundle]].
