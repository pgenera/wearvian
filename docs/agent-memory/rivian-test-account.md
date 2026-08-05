---
name: rivian-test-account
description: Use pg+wearivian@fivesevenfive.org (note spelling) for Rivian testing; never touch the production account pg@fivesevenfive.org
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 1a773a26-8818-4ca0-87c2-81d3e14bc129
---

For testing the wearvian Rivian enrollment/login flow, use the secondary account **`pg+wearivian@fivesevenfive.org`** (note the spelling — "wear**i**vian", NOT "wearvian"; the wrong spelling caused a multi-hour false "UNAUTHENTICATED" debugging detour). The corrected email logs in successfully and returns an MFA (email OTP) challenge — confirming the companion app code is correct. **Never attempt logins against `pg@fivesevenfive.org` — that is the user's production Rivian account.**

**Why:** Failed/automated login attempts can trip Rivian rate-limiting/temporary lockout, which would lock the user out of their real vehicle account. The user explicitly rejected a test against the production email.

**How to apply:** Only run the login harness (`$CLAUDE_JOB_DIR/tmp/rivlogin.py`, stdlib replica of the companion flow) with the `pg+wearvian` account credentials the user provides. Don't guess-spray email variants. See [[rivian-auth-unauthenticated]].
