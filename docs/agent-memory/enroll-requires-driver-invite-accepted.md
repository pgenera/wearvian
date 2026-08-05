---
name: enroll-requires-driver-invite-accepted
description: "Enrollment prereq — the watch's Rivian account must accept a driver/key-share invite in the official app first"
metadata: 
  node_type: memory
  type: project
  originSessionId: 1a773a26-8818-4ca0-87c2-81d3e14bc129
---

Before the watch can be enrolled as a phone key, the Rivian account it uses (the isolated `pg+wearivian@fivesevenfive.org` test account, NOT the production `pg@fivesevenfive.org`) **must first be invited as a driver / key sharer on the R1S from the owner account AND accept that invite in the official Rivian app.** Until then, `getUserInfo` (`currentUser.vehicles`) returns an **empty list** and enrollment reports **0 vehicles** — even though login/MFA/OTP all succeed. This is account-side setup, not a code bug (our getUserInfo query is byte-identical to the reference client).

**Why:** A phone key can only be enrolled against an account that actually has access to the vehicle. An isolated account with no vehicle association has nothing to enroll against.

**How to apply:** When writing user documentation, include this as an explicit setup prerequisite step ("Accept the driver invite for your watch's Rivian account in the official Rivian app before enrolling"). Diagnostic signature in logs: `getUserInfo: ... vehicles=0 vins=[]` with a ~137-byte 200 response. See [[rivian-test-account]] and [[m2-active-command-frame-unknown]].
