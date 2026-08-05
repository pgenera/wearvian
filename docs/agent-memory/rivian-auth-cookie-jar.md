---
name: rivian-auth-unauthenticated
description: "Rivian login UNAUTHENTICATED \"User is unauthenticated\" = bad credentials, not a header/session bug"
metadata: 
  node_type: memory
  type: project
  originSessionId: 1a773a26-8818-4ca0-87c2-81d3e14bc129
---

When the companion's Rivian login returns HTTP 200 with GraphQL error `{"code":"UNAUTHENTICATED","reason":"UNAUTHENTICATED","message":"User is unauthenticated","path":["login"]}`, this means **Rivian rejected the email/password at authentication** — it is NOT a missing-header or session bug. In `bretterer/rivian-python-client` this code maps to `RivianUnauthenticated`; bad-password-for-change is a different code (`BAD_CURRENT_PASSWORD`), and OTP issues are `(UNAUTHENTICATED, OTP_TOKEN_EXPIRED)` / `(BAD_USER_INPUT, INVALID_OTP)`. It also explains a missing MFA prompt: the OTP challenge (`otpToken`) only returns AFTER a successful password step.

**Verified identical to the working reference (don't keep changing headers):** the companion's `RivianAuthClient` now byte-matches the maintained Python client (which the user's Home Assistant integration uses and trusts): URL `https://rivian.com/api/gql/gateway/graphql`; BASE_HEADERS = iOS UA `RivianApp/707 CFNetwork/1237 Darwin/20.4.0` + Accept + Content-Type + `Apollographql-Client-Name: com.rivian.ios.consumer-apollo-ios`; Login adds `Csrf-Token` + `A-Sess`; a `dc-cid: m-ios-{uuid}` header is auto-added per request; tokens read from the response BODY; identical Login/CSRF query strings. Two earlier guesses — a cookie jar, then the `dc-cid` header — did NOT fix it (the gateway sets no cookies; `cookies: stored` never logged). Both were kept as harmless correctness/hygiene, but the remaining cause is the credentials themselves.

**How to confirm/apply:** Run the EXACT credentials through the Python client / HA. If Python also returns UNAUTHENTICATED → credentials are wrong (the companion was given plus-addressed `pg+wearvian@fivesevenfive.org`, not the main account; verify the email the Rivian account is registered under, the password, and that the account is verified). If Python succeeds with the same creds but the app fails → it's environmental (TLS/connection), then add value-level diagnostics (token lengths, full Login response). Note: repeated failed logins trip Rivian rate-limiting/temporary lock (`SESSION_MANAGER_ERROR`), so wait between tries. See [[companion-app-data-layer-contract]].
