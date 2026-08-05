---
name: code-review-before-commit
description: "Review the diff before committing; don't commit then review after"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: e533784a-1d88-4acf-8f1d-58490cfb0c28
---

Before committing a nontrivial change, code-review the diff FIRST — then commit. Don't
commit and only review afterward.

**Why:** in the 0.9.1 icon work I committed + shipped, and the follow-up review then caught a
regression I'd introduced (`KeyTileService.RESOURCES_VERSION` wasn't bumped, so the tile kept the
cached old icons). Catching it pre-commit would have avoided a second build/upload/commit cycle
(0.9.2) to fix it. The user called this out directly (2026-07-03).

**How to apply:** for any nontrivial diff, do a self-review pass (the `/code-review` angles, or at
least: removed-behavior, cross-file callers, and anything the change *implies* elsewhere — e.g. a
resource/version cache, a call site, a test) BEFORE `git commit`. Fix findings, then commit clean.
Cheap changes (a one-line copy tweak) don't need the full pass, but anything touching logic, state,
or shipped assets does. See [[be-sure-before-release-bundle]], [[no-build-by-default]].
