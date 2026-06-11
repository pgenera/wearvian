# Wearvian — Wear OS phone key for Rivian R1S

**Version 0.5** · Pixel Watch 4 (Wear OS 5+)

Wearvian turns a Wear OS watch into a standalone Bluetooth phone key for a Rivian
R1S: it unlocks and drives the vehicle over BLE with no phone present and **no
internet access on the watch** (the app declares no `INTERNET` permission). The
only time a network is involved is a one-time enrollment, which is brokered by a
companion phone app over the Wear OS Data Layer — the watch itself never talks to
the cloud.

This document is written for engineers evaluating the app. It covers setup, a
sketch of day-to-day use, the battery model and the power-saving state machine,
and then the security design: the key-exchange protocol and ciphers, key storage,
off-wrist detection, and how commands and status messages are authenticated. It
deliberately does **not** describe the on-the-wire vehicle protocol.

---

## 1. Setting it up

There are two apps that share one application ID (`org.fivesevenfive.wearvian`):

- **Watch app** — the phone key itself. Runs on the watch.
- **Companion phone app** — used only during enrollment.

Both must be installed and signed by the same developer account; the shared
application ID is what lets them talk over the Wear OS Data Layer.

### One-time enrollment

1. Sign in to the companion phone app with the Rivian account that owns (or has an
   accepted driver invite for) the vehicle. **The account must already have an
   *accepted* driver invitation for the R1S** in the official Rivian app —
   otherwise the cloud returns zero vehicles and there is nothing to enroll.
2. On the watch, start enrollment. The watch generates its key pair (see §6) and
   sends only its **public key** to the phone over the Data Layer.
3. The companion performs the Rivian cloud login and `EnrollPhone` call on the
   watch's behalf, registering the watch's public key as a new phone key, and
   sends the vehicle's identifiers and **public key** back to the watch.
4. The watch stores the enrollment locally and is now a working key.

Enrollment is interactive and infrequent (initial setup, then roughly monthly as
the cloud rotates key material). The watch's private key never leaves the watch at
any point — only the public key is transmitted, and only to the paired phone.

### Day-to-day

After enrollment the companion phone app is no longer needed; the watch works
entirely offline. The vehicle must be reachable over BLE.

---

## 2. Using it

- **Arm / disarm the key.** A toggle in the app (and a Wear OS **tile** for quick
  access) arms the key. While armed, a foreground service maintains the BLE
  presence link so the vehicle recognizes the watch as it approaches —
  passive-entry style: walk up and the doors unlock, sit inside and the vehicle
  authorizes drive. The arming state survives reboots.
- **Status at a glance.** The app shows live vehicle state read over BLE — lock
  state, doors / windows / frunk / liftgate, charge state and estimated charge
  time, state of charge, range, and cabin temperature.
- **Explicit commands.** Lock / unlock and other supported actions can be sent
  directly from the watch.
- **Ongoing notification.** While the key is armed there's a persistent
  notification reflecting the current connection state, with a **Disable** action
  to turn the key off.

---

## 3. Battery implications

Maintaining a passive-entry phone key is inherently more demanding than an idle
app: to be recognized the instant the user reaches the vehicle, the watch must
hold a live, authenticated BLE link and stream a ranging heartbeat several times a
second. Concretely, while the key is **actively** connected to a nearby vehicle
the app:

- holds a `PARTIAL_WAKE_LOCK` so the heartbeat keeps flowing with the screen off,
- runs a foreground service of type `connectedDevice`, and
- streams an RSSI-carrying heartbeat at roughly 3 Hz over a high-priority
  connection interval, plus one link per nearby vehicle location sensor.

That is the expensive state, and it only makes sense when the user is at or
approaching the vehicle. The whole point of the power model below is to be in that
state as little as possible while never making the user wait.

---

## 4. Battery-saving logic

The service has four states:

| State | Wake lock | BLE | Cost |
|-------|-----------|-----|------|
| **Off** | — | — | none (service stopped) |
| **Locked** (off wrist) | released | torn down | foreground service only |
| **Passive** | released | torn down; one hardware-offloaded scan | very low |
| **Active** | held | full presence link(s) + heartbeat | high |

The design principle is: stay in **Passive** whenever the vehicle isn't being
used, and let the hardware — not a polling loop — decide when to wake. Passive
mode keeps the foreground service alive but releases the wake lock and tears down
all GATT connections; in its place a single **hardware-offloaded BLE scan**
watches for the vehicle's advertisement. Because the offloaded scan runs in the
Bluetooth controller, the CPU can sleep. Keeping the foreground service resident
(rather than stopping it) is what lets the app rebuild BLE on approach without a
background foreground-service start, which modern Android restricts.

There are two ways into Passive:

1. **Idle after disconnect.** If no link has been up for 5 minutes (vehicle out of
   range or asleep), drop to Passive.
2. **Parked-idle while still connected.** If the vehicle stays connected but its
   *meaningful* state — lock, doors, windows, frunk, liftgate, charge state, sleep
   — holds steady, drop to Passive even though the link is live. The timeout is
   **5 minutes when plugged in** (a strong "parked at home" signal) and **15
   minutes otherwise** (conservative, so a quick errand stop doesn't idle the key
   out from under the user). Any meaningful change resets the timer — so the door
   you open as you walk up keeps the key active. State that drifts on its own
   (state of charge, range, cabin temperature) is deliberately ignored, since it
   doesn't indicate the user is present.

Waking back to **Active** happens on:

- **Return after departure.** The offloaded scan reports `MATCH_LOST` when the
  vehicle leaves range; after a 30-second debounce (so a brief dropout while
  parked nearby isn't mistaken for leaving) the watch arms a "return" trigger. The
  next `FIRST_MATCH` then means a genuine approach and wakes the link. A
  `FIRST_MATCH` *before* a departure has been seen is just the vehicle the watch
  is still parked beside, and is ignored — this avoids snapping straight back to
  Active.
- **A user command,** which falls back to a one-shot connect and wakes the
  service.
- **Unlock,** for the off-wrist case below.

The parked-idle path is always on; it costs no capability (commands still work via
the one-shot path, and approach still wakes the link), it only stops the
continuous heartbeat when nothing is happening — which is the bulk of the savings.

---

## 5. Security — overview

Wearvian implements Rivian's phone-key cryptography as a 1:1 port of the
community-tested reference implementation, with the secret material bound to the
watch's secure hardware. The sections below describe how the keys are agreed,
where they live, how the app detects the watch leaving the wrist, and how it
authenticates traffic to and from the vehicle. They intentionally omit the vehicle
wire format — only the security envelope is described.

## 6. Key exchange and ciphers

Each watch owns one **secp256r1 (NIST P-256) EC key pair**, generated on-device at
enrollment. Enrollment registers the watch's **public key** with the vehicle (via
the cloud, brokered by the companion); the watch receives the **vehicle's public
key** in return. From that point the two sides share a key without any further
network exchange:

- **Shared secret:** `ECDH(watchPrivateKey, vehiclePublicKey)`.
- **Session/HMAC key:** `HKDF-SHA256` of the ECDH output (32-byte key, null salt,
  empty info — matching the reference).
- **Message authentication:** `HMAC-SHA256` under the HKDF-derived key.
- **Confidential frames:** `AES-128-GCM` (`AES/GCM/NoPadding`, 128-bit tag), used
  for the encrypted command/status channel.

The ECDH itself is performed **inside the secure element** (see §7); only the
resulting shared secret is returned to the app, where the HKDF/HMAC/GCM steps run.
These primitives are unit-tested against fixed parity vectors so they stay
byte-compatible with the reference.

## 7. Key storage

The watch's private key is generated in the **Android Keystore** with
`PURPOSE_AGREE_KEY`, backed by **StrongBox** (a dedicated secure element) when
available — as it is on the Pixel Watch 4 — and falling back to the TEE otherwise.

- The private key is **non-exportable**: it cannot be read out of the Keystore.
  ECDH is computed inside the secure hardware, so the raw private key never enters
  application memory.
- Only the **public key** is ever transmitted, and only to the paired phone over
  the Data Layer during enrollment.
- This is what makes the key resilient: the secret lives only in the watch's
  secure hardware, not in any backup, file, or cloud account.

## 8. Off-wrist detection (anti-theft)

A phone key is only as safe as the watch it lives on, so the app ties key
operation to the watch being unlocked on the wearer's wrist. Wear OS locks the
device when it is removed from the wrist (with a screen lock configured); the app
uses `KeyguardManager.isDeviceLocked` as its off-wrist / locked signal, applied in
two layers:

- **Layer 1 — runtime gating.** While the watch is locked, the app sends **no
  authenticated heartbeats**. The vehicle loses presence within seconds and will
  not passively unlock or authorize drive for whoever is holding the watch. The
  link is kept up so presence resumes instantly when the legitimate wearer unlocks
  it. This protects sessions that are already running (where a shared secret is
  cached in memory).
- **Layer 2 — key-use gating.** Keys are generated with
  `setUnlockedDeviceRequired(true)`, so the Keystore will not even perform the
  initial ECDH while the device is locked. A stolen, locked watch therefore can't
  derive the shared secret to start a *new* session at all.

When the watch is locked the service additionally tears down all BLE and releases
the wake lock (keeping only the foreground service), and rebuilds everything on
unlock.

## 9. Command and status authentication

All meaningful traffic with the vehicle is cryptographically bound to the session,
so neither commands nor status can be forged or replayed without the shared key.

- **Pairing handshake.** Each connection runs a nonce exchange: the watch signs a
  random nonce with `HMAC-SHA256` under the HKDF-derived key, and the vehicle
  returns its own nonce. Both nonces seed the session.

- **Active commands.** Every command (lock, unlock, drive-enable, etc.) is
  authenticated with an `HMAC-SHA256` signature and carries a monotonically
  increasing per-session sequence number, so a captured command can't be replayed
  and a command from outside the session is rejected. Commands ride the live,
  authenticated session rather than a fresh connection, so their sequence numbers
  stay valid mid-session.

- **Ranging heartbeats.** The periodic presence/ranging heartbeats are likewise
  authenticated under the session key and the exchanged nonces — an unauthenticated
  heartbeat won't establish presence.

- **Status / command-ack frames.** The vehicle's confidential status and
  acknowledgement frames are encrypted with `AES-128-GCM`; the app decrypts and
  verifies them with the session key and nonces. (A separate, in-the-clear
  vehicle-status stream carries non-sensitive telemetry such as lock state, charge
  state, and range; it is read but not relied on for authorization.)

The net effect: presence, commands, and confidential status are all gated on
possession of the shared secret, which is itself gated on the watch's
secure-element private key and the watch being unlocked on the wrist.
