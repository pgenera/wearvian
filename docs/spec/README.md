# Specifications

Descriptive specs of what the app does at watch `main` `b19ab03` (0.9.12). They record current
behavior and list where code, comments and older docs disagree; they do not change code.

- [ble-protocol.md](ble-protocol.md): app ↔ car BLE protocol (devices, GATT, handshake, heartbeat,
  status stream, gear detection, commands, MTU, timing). Discrepancies D1 to D15.
- [component-interfaces.md](component-interfaces.md): presence state machine, service API,
  sessions, command path, tile, receivers, stores, watch ↔ companion. Discrepancies D16 to D26.
