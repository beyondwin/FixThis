# Device Connection Control Design

Date: 2026-05-06

## Purpose

The feedback console device control should make the active Android device and
its connection state obvious at a glance. The current control exposes long ADB
serials such as `adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp` in normal UI,
uses a vague `Devices` button, and labels the local selection clear action as
`Disconnect`, which can be confused with running `adb disconnect`.

This design adopts the compact Option B direction from the visual companion:
a single device button that owns identity and status, plus compact refresh and
clear actions.

## Target UI

Topbar device controls render as:

```text
[ ● SM_G986N  Connected ▾ ] [ ↻ ] [ Clear selection ]
```

If topbar space becomes tight, the clear button may display `Clear`, but its
accessible label and tooltip must remain `Clear FixThis device selection`.

The selected device control is one visual unit:

- left: state dot
- primary text: short device label, usually model name
- secondary text: connection state
- right: dropdown affordance

The normal topbar must not show raw ADB serials. Raw serials are allowed only in
secondary dropdown detail text, diagnostics, or error messages where they help
disambiguate duplicate devices.

## Device Labels

Device display label priority:

1. `model`
2. `deviceName`
3. `product`
4. shortened `serial`
5. `Unknown device`

For Wi-Fi ADB serials that contain service-discovery suffixes, the topbar should
not show the raw serial. If no model-like label exists, show a shortened serial
such as `R3CN60LXW3L` or the first stable readable token.

Dropdown rows may include detail:

```text
SM_G986N
connected

Pixel_8
unauthorized · unavailable

emulator-5554
offline · unavailable
```

## Connection States

The UI uses four user-facing states:

| State | Meaning | Color | Topbar copy |
| --- | --- | --- | --- |
| No device | No active FixThis device selection. Live capture is disabled. | gray | `No device` |
| Connecting | User selected a device or refreshed devices and the console is reconciling selection. | amber | `Connecting` |
| Connected | Active selected device exists and ADB reports `state == "device"`. | green | `Connected` |
| Unavailable | Selected/attempted device is offline, unauthorized, missing, or selection failed. | red | `Unavailable` |

The status text should be short in the topbar. More specific causes, such as
`unauthorized`, `offline`, or `not connected`, belong in dropdown row detail or
the console error region.

## Controls

### Device Button

Clicking the device button opens the native select/dropdown or a lightweight
popover, depending on what is practical in the existing console. The first
implementation can keep the existing `<select>` if it is styled to read as a
single compact device control.

Selectable devices are only those whose ADB state is `device`. Unavailable
devices remain visible in the list but disabled, with their state shown in the
detail text.

### Refresh

The existing `Devices` button becomes an icon button:

```text
↻
```

Its tooltip and accessible label are `Refresh devices`. Clicking it calls the
existing `/api/devices` endpoint and refreshes the list, selected state, and
status.

### Clear Selection

The existing `Disconnect` button becomes `Clear selection` or compact `Clear`.

This action clears only FixThis's active device selection and owned bridge
resources. It must not imply or perform `adb disconnect`, detach USB, or affect
the user's ADB server outside FixThis resources. The tooltip/accessibility
copy should say `Clear FixThis device selection`.

## Behavior

Initial load:

- While `/api/devices` is pending, show `Connecting` only if there is already a
  selected serial known locally. Otherwise show `No device`.
- If no devices are returned, show disabled device control with `No device`.
- If devices exist but none is selected, show `No device` and let the user pick
  one.

Selecting a device:

- Set the visual state to `Connecting` immediately.
- POST `/api/device/select`.
- On success, show the short label and `Connected`.
- On failure, preserve or clear selection according to the existing API result,
  show `Unavailable`, and surface the specific server message in the error
  region.

Refreshing devices:

- Refresh is safe and idempotent.
- If the selected device is still present and ready, keep it selected and show
  `Connected`.
- If the selected device disappears or becomes unavailable, show `Unavailable`
  and disable live preview until another ready device is selected.

Clearing selection:

- Clear active FixThis selection.
- Show `No device`.
- Disable live preview polling and capture/navigation surfaces that require a
  device.
- Keep saved sessions and screenshots browsable.

## Visual Styling

Use the existing dark Studio palette:

- connected green: `#6fcf97`
- connecting amber: `#e6b45a`
- unavailable red: `#f26d6d`
- no device gray: `#7d8089`
- surfaces: existing `--bg-1`, `--bg-2`, `--bg-3`, `--line`

The device button should have stable height and width constraints so switching
between `No device`, `SM_G986N`, `Connecting`, and `Unavailable` does not shift
the topbar. The primary label truncates with ellipsis if needed.

## Error Handling

Unavailable states should not hide useful diagnostics. The topbar remains terse,
while the existing `error` region shows the detailed server message. Examples:

- `DEVICE_NOT_AVAILABLE: Android device is not ready: Pixel_8 (unauthorized)`
- `DEVICE_NOT_AVAILABLE: Android device is not connected: adb-R3CN...`

The long serial may appear in these detailed messages because it is diagnostic,
not normal navigation UI.

## Testing

HTML/static tests should assert:

- raw selected serial is not used in normal `deviceStatus` text
- device label helper prefers model/deviceName/product before serial
- `Refresh devices` copy or accessible label exists
- `Clear selection` or `Clear FixThis device selection` exists
- states/copy for `Connected`, `Connecting`, `Unavailable`, and `No device`
  exist in the browser asset
- unavailable devices remain disabled

API tests should remain focused on behavior:

- only `device` state devices are selectable
- selecting missing/offline/unauthorized devices fails without mutating bridge
  selection
- clear selection does not call external `adb disconnect` behavior and leaves
  ADB device discovery intact
