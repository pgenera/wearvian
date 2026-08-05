---
name: write-tests-when-reasonable
description: "Add/update unit tests when changing testable logic — don't just ship code"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: e533784a-1d88-4acf-8f1d-58490cfb0c28
---

Write tests whenever reasonable — when adding or changing logic that's unit-testable, add or update
the tests too, don't just ship the code. (User feedback 2026-07-03, alongside [[code-review-before-commit]].)

**Why:** the repo already has a JVM unit-test suite (`wear/app/src/test/...`, `core-crypto/src/test/...`
— e.g. `VehicleStatusTest`, `PresenceStatusTest`, `RivianCryptoTest`, `ActiveCommandFramesTest`), and
the review turned up stale assertions that had gone red because a behavior change ("link count first")
shipped without updating its test. Tests are the guardrail the user expects kept current.

**How to apply:** pure/parsing/format logic (VehicleStatus bit-decode, PresenceStatus summary strings,
protocol frame builders, crypto) is cheap to test on the JVM — add a case when you touch it, and
update existing assertions when you change intended output. Run `./wear/gradlew -p wear test` (fast,
JVM-only) as part of the pre-commit self-review. UI/Compose, BLE, and Android-framework code isn't
worth mocking out — skip tests there and rely on on-vehicle/debug verification instead. "Reasonable"
= there's testable logic without heavy Android mocking. See [[be-sure-before-release-bundle]].
