---
name: feature-branches-not-production-guards
description: "Build experimental/unvalidated features on a git branch, not BuildConfig.PRODUCTION-guarded on main"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: b194ce1f-01a8-4332-84ac-f74059335d3e
---

When building an experimental or not-yet-validated feature (e.g. the driving-doze wake-lock
optimization, 2026-06-16), do it on a **dedicated git branch** — do NOT land it on `main` gated
behind `BuildConfig.PRODUCTION` (or similar runtime guards).

**Why:** the user wants `main` to stay clean/shippable; in-progress experiments belong on a branch
until validated, rather than accumulating production-guarded dead code on the trunk. (Stated
2026-06-16 after I'd already merged the gated driving-doze into main — they accepted that one but
asked for branches going forward.)

**How to apply:** before starting a feature like this, `git checkout -b wearvian-<feature>` and build
there; merge to main once it's validated on-vehicle. Matches the existing branch convention seen in
[[m2-proximity-wake]] / [[watch-lock-anti-theft]] (branches `wearvian-m2-proximity-wake`, etc.). See
also [[no-build-by-default]], [[build-debugrelease-target]].
