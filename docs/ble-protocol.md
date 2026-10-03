# Bluetooth protocol

The contract between devices. The Android and Apple apps must agree on every
detail here; the message encoding itself is implemented once, in
`core/.../sync/WireCodec.kt` and `Framing.kt`, and shared by all of them.

## Roles

- The **host** is a GATT server (peripheral). It advertises the court service.
- A **guest** is a GATT client (central). It scans for the service, connects,
  switches notifications on, then says hello.

## GATT layout

| Item | UUID | Properties |
| --- | --- | --- |
| Court service | `5ad31000-7c4e-4b6f-9d2a-8e3f1b0c9a71` | Primary service |
| To-host characteristic | `5ad31001-7c4e-4b6f-9d2a-8e3f1b0c9a71` | Write (with response) |
| From-host characteristic | `5ad31002-7c4e-4b6f-9d2a-8e3f1b0c9a71` | Notify |

No pairing or bonding is used, so players never see a system pairing prompt.

## Advertisement

- The advertisement carries the 128-bit court service UUID.
- The host's display label is advertised differently per platform, because
  iOS cannot advertise service data:
  - **Android host:** label as service data for the court service UUID, in
    the scan response, at most 12 bytes of UTF-8.
  - **iPhone host:** label as the local name.
- A scanner reads the label from service data if present, otherwise from the
  local name.
- The join code is **not** advertised.

## Connection sequence

1. Guest connects and, on Android, requests the largest packet size (MTU).
2. Guest discovers the court service and switches notifications on for the
   from-host characteristic. The host treats this as "guest ready".
3. Guest writes `HELLO` to the to-host characteristic.
4. Host replies on the from-host characteristic with `STATE` (admitted) or
   `JOIN_REJECTED`. A rejected guest disconnects itself.
5. From then on the guest writes `COMMAND`s, and the host notifies `STATE`
   to everyone on each change and every two seconds, and `COMMAND_RESULT` to
   the guest whose tap it was.
6. When the host ends the match or stops sharing it, it notifies
   `SESSION_ENDED` and shuts the link down a moment later.

## Packets

Every message is split into packets that fit one Bluetooth write or
notification. The usable size is the negotiated MTU minus 3, and never less
than 20 bytes. Each packet starts with one header byte:

| Bits | Meaning |
| --- | --- |
| 7 | First packet of a message |
| 6 | Last packet of a message |
| 5..0 | Index of the packet within the message, modulo 64 |

A receiver that sees a gap discards the message in progress and waits for the
next one. Nothing is re-requested: the next `STATE` repairs the gap. A message
may not exceed 8 KB.

## Messages

All integers are big-endian. The first byte is the message type.

### `0x01 HELLO` (guest to host)

| Field | Size | Notes |
| --- | --- | --- |
| Protocol version | 1 | Currently 1. These first two bytes never change layout. |
| Device id | 8 | Random, created on first launch |
| Device kind | 1 | 0 phone, 1 watch |
| Join code | 2 | 0 to 9999, or `0xFFFF` for none |
| Name length | 1 | 0 to 24 |
| Name | n | UTF-8 |

### `0x02 COMMAND` (guest to host), always 16 bytes

| Field | Size | Notes |
| --- | --- | --- |
| Command id | 8 | Random and non-zero; identifies retransmissions |
| Epoch | 2 | Hosting epoch the guest is synced to |
| Base version | 4 | Version of the match the guest was looking at |
| Action | 1 | 0 point for team A, 1 point for team B, 2 undo |

### `0x10 STATE` (host to guest)

| Field | Size | Notes |
| --- | --- | --- |
| Match id | 8 | Random |
| Epoch | 2 | Starts at 1; raised when another device takes over as host |
| Version | 4 | Commands accepted so far, undos included |
| Last command id | 8 | The command that produced this version, or 0 |
| Device count | 1 | Devices in the session, host included |
| Format | 9 | See below |
| Point count | 2 | At most 4096 |
| Points | (count + 7) / 8 | One bit per point, least significant bit first; 1 = team B |

Format, one byte each: sport (0 padel, 1 tennis), best of (1, 3, 5), games
per set, deuce rule (0 advantage, 1 golden point, 2 star point), set tiebreak
(0 or 1), tiebreak points, final set rule (0 same as other sets, 1 advantage
set, 2 match tiebreak), match tiebreak points, first server (0 team A,
1 team B).

A receiver rebuilds the score by replaying the points. A state whose points
cannot be replayed (for example, points after the match is won) is rejected.

### `0x11 COMMAND_RESULT` (host to guest)

| Field | Size | Notes |
| --- | --- | --- |
| Command id | 8 | |
| Outcome | 1 | 0 accepted, 1 duplicate, 2 stale, 3 match complete, 4 nothing to undo |
| Version | 4 | Host's version after handling the command |

### `0x12 JOIN_REJECTED` (host to guest)

| Field | Size | Notes |
| --- | --- | --- |
| Reason | 1 | 0 wrong code, 1 session full, 2 unsupported version |

### `0x13 SESSION_ENDED` (host to guest)

No fields. The host is closing the court. A guest keeps the last score on
screen, disconnects, and does not try to reconnect.

## Rules

**Host**
- Accept a command only if its epoch and base version equal the host's
  current epoch and version. Otherwise answer `stale`.
- A command id already applied is answered `duplicate` and changes nothing.
- On acceptance, notify `STATE` to every guest first, then `COMMAND_RESULT`
  to the sender.
- Ignore commands from a device that has not been admitted.
- Malformed input is dropped and must never crash the host.

**Guest**
- Ignore a `STATE` with a lower epoch than the one held for the same match,
  or a lower version within the same epoch.
- Show taps immediately, before the host confirms them.
- Send taps one at a time: the next tap goes out only after the previous one
  appears in a `STATE`. If the match moves on without the pending tap, drop
  every waiting tap.
- After reconnecting, wait for the first `STATE`, then re-send the oldest
  unresolved tap if it still applies.

## Changing the protocol

Any change to a layout above is incompatible: raise `PROTOCOL_VERSION` in
`WireCodec.kt`. A host turns away a guest with a different version using
`JOIN_REJECTED`, so players see "update the app" rather than a silent
failure.
