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
| Protocol version | 1 | Currently 2. These first two bytes never change layout. |
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
| Action | 1 | 0 point for team A, 1 point for team B, 2 undo, 3 swap team A's server, 4 swap team B's server |

### `0x10 STATE` (host to guest)

| Field | Size | Notes |
| --- | --- | --- |
| Match id | 8 | Random |
| Epoch | 2 | Starts at 1; raised when another device takes over as host |
| Version | 4 | Commands accepted so far: points, undos and server swaps |
| Last command id | 8 | The command that produced this version, or 0 |
| Device count | 1 | Devices in the session, host included |
| Flags | 1 | Bit 0: the receiving device may change the score. Bit 1: team A's serving order is swapped. Bit 2: team B's is. Other bits must be 0. |
| Format | 11 | See below |
| Players | 2 or more | See below |
| Point count | 2 | At most 4096 |
| Points | (count + 7) / 8 | One bit per point, least significant bit first; 1 = team B |

Format, one byte each: sport (0 padel, 1 tennis), best of (1, 3, 5), games
per set, deuce rule (0 advantage, 1 golden point, 2 star point), set tiebreak
(0 or 1), tiebreak points, final set rule (0 same as other sets, 1 advantage
set, 2 match tiebreak), match tiebreak points, first server (0 team A,
1 team B), play all sets (0 or 1: keep playing after the match is decided),
doubles (0 or 1).

Players: for team A and then team B, a count (0 to 2) followed by that many
names, each a length byte (1 to 20) and UTF-8 text. A team's first-listed
player serves its first service game of a set, unless its flag above says the
order is swapped. Names are labels only; nothing in the scoring depends on
them.

The permission flag is the one field that can differ between two guests'
copies of the same state: the host sets it per device.

A receiver rebuilds the score by replaying the points. A state whose points
cannot be replayed (for example, points after the match is won) is rejected.

### `0x11 COMMAND_RESULT` (host to guest)

| Field | Size | Notes |
| --- | --- | --- |
| Command id | 8 | |
| Outcome | 1 | 0 accepted, 1 duplicate, 2 stale, 3 match complete, 4 nothing to undo, 5 same rally, 6 not allowed |
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
- A point from one device arriving less than 4 seconds after an accepted
  point from a different device is answered `same rally` and changes
  nothing. Undo is exempt, and so are consecutive points from one device.
- A command from a device the host has made view-only is answered
  `not allowed` and changes nothing. The permission belongs to the device id,
  so it survives a reconnection.
- A change of players' names is not a command: the host sends a `STATE` with
  the same version and the new names.
- A device that takes over as host from a guest's copy of the match raises
  the epoch by a random 1 to 64 and advertises under the name and join code
  of the court it replaces.
- A host looks for other courts under its own name. If one turns out to
  carry the same match, the court with more devices continues; with equal
  numbers the later epoch; with equal epochs the higher version. The other
  host stops hosting without sending `SESSION_ENDED` and joins as a guest.
  The host that continues raises its epoch above the other's.
- On acceptance, notify `STATE` to every guest first, then `COMMAND_RESULT`
  to the sender.
- Ignore commands from a device that has not been admitted.
- Malformed input is dropped and must never crash the host.

**Guest**
- Ignore a `STATE` with a lower epoch than the one held for the same match,
  or a lower version within the same epoch.
- Show taps immediately, before the host confirms them.
- When the last `STATE` said this device may not change the score, send
  nothing and tell the player the device is view-only.
- Send taps one at a time: the next tap goes out only after the previous one
  appears in a `STATE`. If the match moves on without the pending tap, drop
  every waiting tap.
- After reconnecting, wait for the first `STATE`, then re-send the oldest
  unresolved tap if it still applies.
- A guest that has lost its host also scans for a court with the same name
  and tries it with the same join code. It stays only if the first `STATE`
  carries the match id it already holds, with an epoch that is not lower
  and, at the same epoch, a version that is not lower. Otherwise it
  disconnects and keeps looking, and treats a `JOIN_REJECTED` there as "not
  my court", not as a refusal to show the player.

## Changing the protocol

Any change to a layout above is incompatible: raise `PROTOCOL_VERSION` in
`WireCodec.kt`. A host turns away a guest with a different version using
`JOIN_REJECTED`, so players see "update the app" rather than a silent
failure.
