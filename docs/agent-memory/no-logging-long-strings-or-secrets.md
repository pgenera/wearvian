---
name: no-logging-long-strings-or-secrets
description: "Don't log long strings (keys, tokens, full IDs) in debug logs; never log secrets"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: b194ce1f-01a8-4332-84ac-f74059335d3e
---

Don't emit long strings to the debug/logcat sink — and never secrets. Public keys,
tokens, full vasIds, base64 blobs, etc. don't belong in `DebugLog.add(...)` /`logi`
lines, even truncated. Log a boolean/mode/length instead.

**Why:** The user flagged it twice — once on a probe that dumped a getUserInfo
response, once on a `keyMode` line that appended a truncated public key. Long log
lines are noise; secret-bearing ones are a leak risk.

**How to apply:** When adding diagnostics, log state *names* not state *values*
(e.g. `keyMode=IMPORTED` not the key; `tokenPresent=true` not the token). Pre-existing
lines that dump whole data classes (e.g. `enrollment=$enrollment`) are not a license
to add more. Relates to [[rivian-test-account]].
