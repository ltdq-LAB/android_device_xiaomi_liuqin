# LiuqinParts

`LiuqinParts` is liuqin's platform-signed, persistent `system_ext` host for the
stylus settings UI and Kotlin helper. Hardware conclusions in this module come
from the liuqin OS3.0.7.0 APK/framework binaries and the shipped, unstripped
`nt36532_touch.ko`; behavior from another Xiaomi device is not treated as a
hardware contract.

The helper keeps LCD double-tap aligned with
`Settings.Secure.DOZE_DOUBLE_TAP_GESTURE` through stock TouchFeature mode 14.
It separately maps the stock `Settings.System.stylus_quick_note_screen_off`
switch to mode 24 and consumes the dedicated
`org.lineageos.sensor.stylus_quick_note` pen sensor to wake the display.
SensorService activation only controls one-shot event delivery for those
gestures. Pen Hall wake remains gated by its standard wake sensor subscription
so an awake Hall edge cannot become a stale wake event.
The LCD HAL uses dedicated `gesture_*_subscribed` nodes for that delivery
state; the existing `gesture_*_enabled` nodes continue to expose the persistent
IC mode and therefore stay `1` while the corresponding setting is enabled.

The app runs in the dedicated `liuqin_parts_app` SELinux domain. It receives
qcom-battery uevents and reads the precisely labelled
`pen_connect_strategy` node, but it cannot open `/dev/xiaomi-touch`. Touch mode
traffic goes through the device's compatibility implementation of the exact
nine-method `vendor.xiaomi.hw.touchfeature@1.0` HIDL interface.

## Stock-derived behavior

- `POWER_SUPPLY_PEN_MAC` is accepted only by the one-shot gate armed when
  reverse-pen charge state changes to 4.
  Existing bonds use the stock all-profile `BluetoothDevice.connect()` path;
  new pens use LE bonding and Bluetooth is never enabled automatically.
- The stored-address key, old-pen removal filter/debounce, HID-host state gate,
  standard Battery Service `2A19` read/notification, Hall polarity, and all
  seven framework VID/PID mappings follow the liuqin binaries.
- Hall 3 or Hall 4 low means attached; both high means detached. Both fields
  must occur in the same uevent. The first complete sample is only a baseline.
- An active `pen_connect_strategy` selects from the stock ordered display list
  `[120, 60]`, capped by the normal refresh policy. Thus a normal 144/120 Hz
  cap selects 120 Hz, a 90 Hz cap selects 60 Hz, and a 60 Hz cap selects 60 Hz.
  It is not an unconditional 120 Hz override.
- The stock touch module enables the pen firmware path with host command
  `0x7B, boolean`. No numeric 240 Hz value is sent by Android; the pen cadence
  is firmware-selected. Stock mode 9 is a zero-range no-op and is not reused as
  a report-rate selector.
- PAGE_DOWN (93) is the primary button and PAGE_UP (92) is secondary. Both use
  a 380 ms hold threshold. Quick note then waits up to 10 seconds for a new pen
  `ACTION_DOWN` while the button remains held; screenshot runs at the hold
  threshold. The page-key events remain visible to the foreground app, matching
  the stock type-1/type-2 path.

Lineage lacks Xiaomi's SurfaceFlinger hook, so the display controller is a
best-available approximation: it uses the non-persisting Android display-mode
API and restores the stored normal policy when the pen path exits. It cannot
reproduce Xiaomi's private mode-group or per-layer ranking inside
SurfaceFlinger.

## Deliberate Lineage additions

- On a real detached-to-attached Hall edge, report pairing/connection state in
  a toast. A battery value is shown only after HID Host is connected, using the
  newest valid charger, standard GATT, or framework battery observation. A
  connected pen without a valid sample is reported as connected without an
  invented placeholder value.
- The existing mode-8 pen-detach wake sensor is extended to both real aggregate
  Hall edges so attaching and removing the pen can light the display. OS3 does
  not normally light the display for either Hall edge; this is the explicitly
  requested Lineage behavior, not a stock claim.
- The LCD sensors sub-HAL gates pen click, double tap and pen pickup event
  delivery on the keyboard-cover Hall switch (SW_LID): with the cover closed
  these wake chains do not fire, so the open cover is the precondition for
  them. An undeterminable lid state never suppresses a gesture.
- The multihal proxy gates the SSC doze pickup pulse on the same Hall switch
  with `ro.vendor.sensors.xiaomi.pickup_lid_gate`: with the cover closed,
  picking the tablet up must not light the display. A dropped wakeup event
  releases its wakelock without reaching the framework.
- Button actions are user-selectable. Stock only teaches fixed quick-note and
  screenshot shortcuts; the extra mappings are Lineage functionality.

## Button settings

The page is injected into **Settings > System** at order `-254`, immediately
after Keyboard's `-255` bucket. It shares `-254` with pointing-device entries
and sorts ahead of them in the bundled English and Simplified Chinese titles.
Another localized title in the same bucket can affect absolute adjacency, so
the implementation avoids a fragile full overlay of Settings' dashboard XML.

The UI stores validated tokens in `Settings.Secure`:

| Button | Key | Default |
| --- | --- | --- |
| Primary | `liuqin_stylus_primary_button_action` | `open_notes` |
| Secondary | `liuqin_stylus_secondary_button_action` | `screenshot` |

Supported values are `none`, `open_notes`, `screenshot`, `back`, `home`,
`recents`, and `media_play_pause`. Event recognition runs in the device key
handler loaded into `system_server`; the preference fragment only validates and
stores settings.

## Hardware-dependent omissions

Firmware update is deliberately outside LiuqinParts' scope. The helper neither
contacts Xiaomi's OTA service nor downloads or flashes FE59 DFU payloads. It
contains no Xiaomi OTA application secret and does not request Internet access.

The stock app also conditionally observes FE10/FE11 MIPP events. Static liuqin
evidence does not establish that its VID-6421 pens expose those services in
normal mode, so proprietary frequency/hibernation writes are deferred until an
actual GATT service capture exists. The core path does implement stock's
address-filtered pre-bond BLE scan and reads standard Battery `2A19`, Firmware
Revision `2A28`, and PNP ID `2A50` characteristics for connection and device
information. Pairing reliability still needs hardware validation.

The compatibility HIDL implements the stock 1032-byte abstract
`SOCK_SEQPACKET` `touchevent` client protocol and returns an empty event when
its producer is absent. The stock producer is a separate diagnostic telemetry
daemon, not a stylus controller; its input-event algorithms depend on liuqin
sysfs interfaces which are not present in the compact Lineage driver, so no
synthetic producer data is generated. The exact liuqin nt36532 module leaves
its long-value callback unset, so valid long-value calls preserve the stock
successful no-op behavior instead of adding an invented controller command.
Unsupported nonzero tuning-mode resets return `EINVAL` instead of inventing a
`GET_CUR` value for hardware which the compact backend cannot tune.
