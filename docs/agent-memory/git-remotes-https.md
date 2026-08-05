---
name: git-remotes-https
description: "On this host, push the wearvian repos over HTTPS (gh provides creds); the default SSH remotes fail"
metadata: 
  node_type: memory
  type: project
  originSessionId: b194ce1f-01a8-4332-84ac-f74059335d3e
---

Push the wearvian repos over **HTTPS, not SSH**, on this host. `gh` is authenticated (account
`pgenera`, HTTPS protocol, token with `repo` scope) and provides the git credential helper, so HTTPS
pushes just work. The repos' `origin` defaults to **SSH** (`git@github.com:pgenera/wearvian.git` and
`…/wearvian-companion.git`), which fails with `Host key verification failed` (github.com's SSH host
key isn't trusted here). Fix per repo:

```
git remote set-url origin https://github.com/pgenera/<repo>.git   # wearvian / wearvian-companion
```

Also: the **watch** repo had no git identity configured (commits failed with "Author identity
unknown"). Set it to match companion: `user.name=pgenera`, `user.email=pg@fivesevenfive.org`.

Two repos, branch `main` tracks `origin/main` in both. See [[toolchain-setup]] for the repo layout.
