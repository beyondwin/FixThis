# Device Connection Control Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current long-serial device picker/status/buttons with a compact Option B device control that clearly shows `No device`, `Connecting`, `Connected`, and `Unavailable`.

**Architecture:** Keep the existing console device APIs and native `<select>` behavior. Wrap the select in a styled device control that renders a short device label and connection state, then restyle `Devices` as `↻` refresh and rename `Disconnect` to `Clear selection` with accurate accessibility copy.

**Tech Stack:** Kotlin static HTML asset in `FeedbackConsoleAssets.kt`, Kotlin JVM tests in `FeedbackConsoleServerTest.kt`, existing `/api/devices`, `/api/device/select`, and `/api/device/disconnect` endpoints.

---

## File Structure

- Modify `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/console/FeedbackConsoleAssets.kt`
  - Add device control CSS classes.
  - Replace topbar device markup.
  - Add device UI state helpers and short-label helpers.
  - Update refresh/select/clear behavior and copy.
- Modify `fixthis-mcp/src/test/kotlin/io/github/fixthis/mcp/console/FeedbackConsoleServerTest.kt`
  - Update existing static HTML assertions.
  - Add focused static tests for device state copy, helper functions, and no raw selected serial in normal status text.

The first implementation should not modify server API models. The current `ConsoleDevice` payload already contains `serial`, `state`, `model`, `product`, `deviceName`, and `selected`, which is enough for the target UI.

---

### Task 1: Update Static HTML Tests For The New Device Contract

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/github/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [x] **Step 1: Replace the old device assertions in `consoleHtmlIncludesSelectionHandoffWorkspace`**

In `FeedbackConsoleServerTest.kt`, replace the device-related assertion block inside `consoleHtmlIncludesSelectionHandoffWorkspace` with:

```kotlin
assertTrue(html.contains("id=\"deviceControl\""))
assertTrue(html.contains("id=\"devicePicker\""))
assertTrue(html.contains("id=\"deviceName\""))
assertTrue(html.contains("id=\"deviceConnectionState\""))
assertTrue(html.contains("id=\"refreshDevicesButton\""))
assertTrue(html.contains("id=\"disconnectDeviceButton\""))
assertTrue(html.contains("aria-label=\"Android device\""))
assertTrue(html.contains("aria-label=\"Refresh devices\""))
assertTrue(html.contains("title=\"Refresh devices\""))
assertTrue(html.contains("Clear selection"))
assertTrue(html.contains("aria-label=\"Clear FixThis device selection\""))
assertTrue(html.contains("title=\"Clear FixThis device selection\""))
assertTrue(html.contains("/api/devices"))
assertTrue(html.contains("/api/device/select"))
assertTrue(html.contains("/api/device/disconnect"))
assertTrue(html.contains("function refreshDevices"))
assertTrue(html.contains("function selectDevice"))
assertTrue(html.contains("function disconnectDevice"))
assertTrue(html.contains("function deviceLabel"))
assertTrue(html.contains("function shortenDeviceSerial"))
assertTrue(html.contains("function setDeviceUiState"))
assertTrue(html.contains("function deviceOptionLabel"))
assertTrue(html.contains("option.textContent = deviceOptionLabel(device);"))
assertFalse(html.contains("deviceStatus.textContent = selected ? 'Selected ' + selected.serial"))
assertFalse(html.contains("Selected ' + selected.serial"))
```

Keep the unrelated assertions in the same test intact.

- [x] **Step 2: Add a focused static test for connection state copy**

Add this test near the other `consoleHtml...` tests:

```kotlin
@Test
fun consoleHtmlShowsReadableDeviceConnectionStates() {
    val html = FeedbackConsoleAssets.indexHtml

    assertTrue(html.contains("const DeviceUiState = {"))
    assertTrue(html.contains("NONE: 'none'"))
    assertTrue(html.contains("CONNECTING: 'connecting'"))
    assertTrue(html.contains("CONNECTED: 'connected'"))
    assertTrue(html.contains("UNAVAILABLE: 'unavailable'"))
    assertTrue(html.contains("DeviceStateCopy = {"))
    assertTrue(html.contains("No device"))
    assertTrue(html.contains("Connecting"))
    assertTrue(html.contains("Connected"))
    assertTrue(html.contains("Unavailable"))
    assertTrue(html.contains("data-connection-state=\"none\""))
    assertTrue(html.contains("deviceControl.dataset.connectionState = uiState;"))
    assertTrue(html.contains("deviceConnectionState.textContent = DeviceStateCopy[uiState];"))
    assertTrue(html.contains("const state = { session: null, preview: null, selectedDeviceSerial: null, devices: [] };"))
    assertTrue(html.contains("state.devices = devices;"))
    assertTrue(html.contains("setDeviceUiState(DeviceUiState.CONNECTING, deviceBySerial(state.devices, option.value));"))
}
```

- [x] **Step 3: Add a focused static test for Wi-Fi ADB serial shortening**

Add this test near the device tests:

```kotlin
@Test
fun consoleHtmlShortensWifiAdbSerialsForNormalDeviceLabels() {
    val html = FeedbackConsoleAssets.indexHtml

    assertTrue(html.contains("function shortenDeviceSerial(serial)"))
    assertTrue(html.contains("withoutServiceSuffix = raw.split('._adb-tls-connect._tcp')[0];"))
    assertTrue(html.contains("if (withoutServiceSuffix.startsWith('adb-')) return withoutServiceSuffix.substring(4);"))
    assertTrue(html.contains("return device.model || device.deviceName || device.product || shortenDeviceSerial(device.serial) || 'Unknown device';"))
}
```

- [x] **Step 4: Run the focused tests and verify they fail**

Run:

```bash
./gradlew :fixthis-mcp:test --tests io.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest.consoleHtmlIncludesSelectionHandoffWorkspace --tests io.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest.consoleHtmlShowsReadableDeviceConnectionStates --tests io.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest.consoleHtmlShortensWifiAdbSerialsForNormalDeviceLabels
```

Expected result: tests fail because `deviceControl`, connection state helpers, and serial shortening are not implemented yet.

---

### Task 2: Replace Device Topbar Markup With The Compact Device Control

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/console/FeedbackConsoleAssets.kt`

- [x] **Step 1: Replace the device markup in `.studio-context`**

Replace this block:

```html
<select id="devicePicker"></select>
<select id="previewIntervalSelect" aria-label="Preview interval">
  <option value="manual">Manual</option>
  <option value="1000">1s</option>
  <option value="2000" selected>2s</option>
  <option value="5000">5s</option>
</select>
<button id="refreshDevicesButton">Devices</button>
<button id="disconnectDeviceButton">Disconnect</button>
<span id="deviceStatus" class="status-pill">No device selected</span>
```

with:

```html
<div id="deviceControl" class="device-control" data-connection-state="none">
  <span class="device-state-dot" aria-hidden="true"></span>
  <span class="device-copy">
    <span id="deviceName" class="device-name">No device</span>
    <span id="deviceConnectionState" class="device-connection-state">No device</span>
  </span>
  <span class="device-chevron" aria-hidden="true">▾</span>
  <select id="devicePicker" aria-label="Android device"></select>
</div>
<select id="previewIntervalSelect" aria-label="Preview interval">
  <option value="manual">Manual</option>
  <option value="1000">1s</option>
  <option value="2000" selected>2s</option>
  <option value="5000">5s</option>
</select>
<button id="refreshDevicesButton" class="icon-button" type="button" title="Refresh devices" aria-label="Refresh devices">↻</button>
<button id="disconnectDeviceButton" class="clear-device-button" type="button" title="Clear FixThis device selection" aria-label="Clear FixThis device selection">Clear selection</button>
<span id="deviceStatus" class="status-pill" hidden>No device</span>
```

Keep `deviceStatus` hidden for compatibility with existing JS references and tests during the first pass. The visible state should come from `deviceName` and `deviceConnectionState`.

- [x] **Step 2: Add DOM constants**

After:

```javascript
const devicePicker = document.getElementById('devicePicker');
const deviceStatus = document.getElementById('deviceStatus');
```

add:

```javascript
const deviceControl = document.getElementById('deviceControl');
const deviceName = document.getElementById('deviceName');
const deviceConnectionState = document.getElementById('deviceConnectionState');
```

- [x] **Step 3: Run the focused tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests io.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest.consoleHtmlIncludesSelectionHandoffWorkspace
```

Expected result: this test still fails until CSS and JS helper assertions are implemented.

---

### Task 3: Add Device Control CSS

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/console/FeedbackConsoleAssets.kt`

- [x] **Step 1: Add device CSS after the generic `select, button` styles**

Add this CSS after the existing `button:disabled` rule:

```css
.device-control {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 210px;
  max-width: 260px;
  min-height: 32px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--bg-2);
  color: var(--txt-0);
  overflow: hidden;
}
.device-control:focus-within {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px rgba(184, 211, 106, .12);
}
.device-state-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  flex: 0 0 auto;
  background: var(--txt-2);
}
.device-copy {
  display: grid;
  gap: 1px;
  min-width: 0;
  line-height: 1.1;
}
.device-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  font-weight: 700;
  color: var(--txt-0);
}
.device-connection-state {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0;
  color: var(--txt-2);
}
.device-chevron {
  margin-left: auto;
  color: var(--txt-2);
  font-size: 11px;
  flex: 0 0 auto;
}
.device-control select {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0;
  cursor: pointer;
}
.device-control[data-connection-state="connected"] {
  border-color: rgba(111, 207, 151, .34);
}
.device-control[data-connection-state="connected"] .device-state-dot {
  background: #6fcf97;
  box-shadow: 0 0 0 3px rgba(111, 207, 151, .16);
}
.device-control[data-connection-state="connected"] .device-connection-state {
  color: #6fcf97;
}
.device-control[data-connection-state="connecting"] {
  border-color: rgba(230, 180, 90, .34);
}
.device-control[data-connection-state="connecting"] .device-state-dot {
  background: #e6b45a;
  box-shadow: 0 0 0 3px rgba(230, 180, 90, .16);
}
.device-control[data-connection-state="connecting"] .device-connection-state {
  color: #e6b45a;
}
.device-control[data-connection-state="unavailable"] {
  border-color: rgba(242, 109, 109, .34);
}
.device-control[data-connection-state="unavailable"] .device-state-dot {
  background: #f26d6d;
  box-shadow: 0 0 0 3px rgba(242, 109, 109, .16);
}
.device-control[data-connection-state="unavailable"] .device-connection-state {
  color: #f26d6d;
}
.device-control[data-connection-state="none"] .device-state-dot {
  background: var(--txt-2);
}
.icon-button {
  width: 32px;
  padding: 0;
  display: inline-grid;
  place-items: center;
  font-size: 15px;
}
.clear-device-button {
  white-space: nowrap;
}
```

- [x] **Step 2: Update compact media CSS**

Inside the existing `@media (max-width: 1099px)` block, replace:

```css
#devicePicker {
  min-width: 0;
  width: 100%;
}
```

with:

```css
.device-control {
  min-width: 150px;
  max-width: 190px;
  width: 100%;
}
.device-name {
  font-size: 11px;
}
.device-connection-state {
  font-size: 9px;
}
.clear-device-button {
  max-width: 64px;
  overflow: hidden;
  text-overflow: ellipsis;
}
```

- [x] **Step 3: Run the focused static tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests io.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest.consoleHtmlIncludesSelectionHandoffWorkspace
```

Expected result: failures remain only for JS helper/state assertions.

---

### Task 4: Implement Browser State, Device Label, And State Helpers

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/console/FeedbackConsoleAssets.kt`

- [x] **Step 1: Extend browser state to cache the latest device payload**

Replace:

```javascript
const state = { session: null, preview: null, selectedDeviceSerial: null };
```

with:

```javascript
const state = { session: null, preview: null, selectedDeviceSerial: null, devices: [] };
```

- [x] **Step 2: Add state constants after DOM variables**

After the `let suppressNextClick = false;` line, add:

```javascript
const DeviceUiState = {
  NONE: 'none',
  CONNECTING: 'connecting',
  CONNECTED: 'connected',
  UNAVAILABLE: 'unavailable'
};

const DeviceStateCopy = {
  [DeviceUiState.NONE]: 'No device',
  [DeviceUiState.CONNECTING]: 'Connecting',
  [DeviceUiState.CONNECTED]: 'Connected',
  [DeviceUiState.UNAVAILABLE]: 'Unavailable'
};
```

- [x] **Step 3: Replace the current `deviceLabel` and `deviceOptionLabel` helpers**

Replace the existing helper block:

```javascript
function deviceLabel(device) {
  return device.model || device.deviceName || device.product || device.serial || 'Unknown device';
}

function deviceOptionLabel(device) {
  return deviceLabel(device) + (device.state === 'device' ? '' : ' - ' + device.state + ' (unavailable)');
}
```

with:

```javascript
function shortenDeviceSerial(serial) {
  const raw = String(serial || '').trim();
  if (!raw) return '';
  const withoutServiceSuffix = raw.split('._adb-tls-connect._tcp')[0];
  if (withoutServiceSuffix.startsWith('adb-')) return withoutServiceSuffix.substring(4);
  if (withoutServiceSuffix.length <= 24) return withoutServiceSuffix;
  return withoutServiceSuffix.slice(0, 10) + '...' + withoutServiceSuffix.slice(-6);
}

function deviceLabel(device) {
  if (!device) return 'No device';
  return device.model || device.deviceName || device.product || shortenDeviceSerial(device.serial) || 'Unknown device';
}

function deviceDetail(device) {
  if (!device) return 'No device';
  if (device.state === 'device') return 'connected';
  return (device.state || 'unknown') + ' · unavailable';
}

function deviceOptionLabel(device) {
  return deviceLabel(device) + ' - ' + deviceDetail(device);
}

function setDeviceUiState(uiState, device = null) {
  deviceControl.dataset.connectionState = uiState;
  deviceName.textContent = device ? deviceLabel(device) : 'No device';
  deviceConnectionState.textContent = DeviceStateCopy[uiState];
  deviceStatus.textContent = deviceName.textContent + ' - ' + deviceConnectionState.textContent;
}

function deviceBySerial(devices, serial) {
  if (!serial) return null;
  return (devices || []).find(device => device.serial === serial) || null;
}
```

- [x] **Step 4: Run helper-focused tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests io.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest.consoleHtmlShowsReadableDeviceConnectionStates --tests io.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest.consoleHtmlShortensWifiAdbSerialsForNormalDeviceLabels
```

Expected result: helper-focused tests pass; render behavior may still fail in the broader workspace test if `renderDeviceList` has not been updated.

---

### Task 5: Update Device Rendering And Actions

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/console/FeedbackConsoleAssets.kt`

- [x] **Step 1: Replace `renderDeviceList`**

Replace the full existing `renderDeviceList(payload)` function with:

```javascript
function renderDeviceList(payload) {
  const devices = payload.devices || [];
  state.devices = devices;
  const previousSelectedDeviceSerial = state.selectedDeviceSerial;
  devicePicker.innerHTML = '';

  if (!devices.length) {
    const selectedSerial = null;
    if (previousSelectedDeviceSerial !== selectedSerial) invalidatePreviewContext();
    state.selectedDeviceSerial = selectedSerial;
    const option = document.createElement('option');
    option.value = '';
    option.textContent = 'No devices available';
    devicePicker.appendChild(option);
    devicePicker.disabled = true;
    setDeviceUiState(DeviceUiState.NONE);
    return;
  }

  const selectedSerialFromPayload = payload.selectedSerial || null;
  const selected = devices.find(device => device.selected || device.serial === selectedSerialFromPayload) || null;
  devicePicker.disabled = false;

  if (!selected) {
    const option = document.createElement('option');
    option.value = '';
    option.textContent = 'Select device...';
    option.disabled = true;
    option.selected = true;
    devicePicker.appendChild(option);
  }

  devices.forEach(device => {
    const option = document.createElement('option');
    option.value = device.serial;
    option.textContent = deviceOptionLabel(device);
    option.disabled = device.state !== 'device';
    option.selected = Boolean(device.selected) || device.serial === selectedSerialFromPayload;
    devicePicker.appendChild(option);
  });

  const selectedSerial = selected ? selected.serial : null;
  if (previousSelectedDeviceSerial !== selectedSerial) invalidatePreviewContext();
  state.selectedDeviceSerial = selectedSerial;

  if (!selected) {
    setDeviceUiState(DeviceUiState.NONE);
  } else if (selected.state === 'device') {
    setDeviceUiState(DeviceUiState.CONNECTED, selected);
  } else {
    setDeviceUiState(DeviceUiState.UNAVAILABLE, selected);
  }
}
```

- [x] **Step 2: Replace `refreshDevices`**

Replace:

```javascript
async function refreshDevices() {
  renderDeviceList(await requestJson('/api/devices'));
}
```

with:

```javascript
async function refreshDevices() {
  if (state.selectedDeviceSerial) {
    setDeviceUiState(DeviceUiState.CONNECTING, deviceBySerial(state.devices, state.selectedDeviceSerial));
  }
  renderDeviceList(await requestJson('/api/devices'));
}
```

This intentionally shows `Connecting` only when there is an active selection being reconciled. With no selected serial, refresh leaves the visible state as `No device` until the payload arrives.

- [x] **Step 3: Replace `selectDevice`**

Replace the full existing `selectDevice()` function with:

```javascript
async function selectDevice() {
  const option = devicePicker.selectedOptions[0];
  if (!option || !option.value || option.disabled) return;
  const selectedDevice = deviceBySerial(state.devices, option.value);
  setDeviceUiState(DeviceUiState.CONNECTING, selectedDevice);
  invalidatePreviewContext();
  try {
    renderDeviceList(await requestJson('/api/device/select', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ serial: option.value })
    }));
    await refreshPreview();
    startLivePreviewPolling();
  } catch (cause) {
    setDeviceUiState(DeviceUiState.UNAVAILABLE, { serial: option.value });
    throw cause;
  }
}
```

- [x] **Step 4: Replace `disconnectDevice`**

Replace:

```javascript
async function disconnectDevice() {
  invalidatePreviewContext();
  renderDeviceList(await requestJson('/api/device/disconnect', { method: 'POST' }));
  render();
  startLivePreviewPolling();
}
```

with:

```javascript
async function disconnectDevice() {
  invalidatePreviewContext();
  setDeviceUiState(DeviceUiState.NONE);
  renderDeviceList(await requestJson('/api/device/disconnect', { method: 'POST' }));
  render();
  startLivePreviewPolling();
}
```

- [x] **Step 5: Run focused tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests io.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest.consoleHtmlIncludesSelectionHandoffWorkspace --tests io.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest.consoleHtmlShowsReadableDeviceConnectionStates --tests io.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest.consoleHtmlShortensWifiAdbSerialsForNormalDeviceLabels
```

Expected result: all three focused static tests pass.

---

### Task 6: Verify API Behavior Still Matches Existing Device Semantics

**Files:**
- Test only: `fixthis-mcp/src/test/kotlin/io/github/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [x] **Step 1: Run existing device API tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests io.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest.devicesApiListsAndSelectsActiveDevice --tests io.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest.deviceSelectApiReturnsBadRequestForBlankSerial --tests io.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest.deviceSelectApiReturnsConflictForMissingSerialWithoutChangingSelection --tests io.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest.deviceSelectApiReturnsConflictForOfflineDeviceWithoutChangingSelection
```

Expected result: pass. The implementation must not change server selection behavior.

- [x] **Step 2: Run the broader console HTML test set**

Run:

```bash
./gradlew :fixthis-mcp:test --tests io.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest
```

Expected result: pass. If tests fail because old visible copy such as `Devices`, `Disconnect`, or `Selected ` is asserted elsewhere, update those tests to the new spec copy rather than restoring old UI text.

---

### Task 7: Manual Browser QA

**Files:**
- No source changes unless QA finds a defect.

QA defect note, 2026-05-06: fixed stale live preview after a refresh turns the selected device unavailable/no-device. `renderDeviceList` now rerenders the preview region after invalidating selection context, covered by `consoleHtmlRerendersPreviewWhenDeviceSelectionInvalidatesPreview`.

Manual QA note, 2026-05-06: started the feedback console at `http://127.0.0.1:57082` after `:fixthis-mcp:installDist`; `adb` is not available in this environment, so physical USB/Wi-Fi ADB connection persistence could not be verified. Browser QA covered real no-device console rendering plus a synthetic local console harness for ready, connecting, connected, unavailable, refresh, and clear-selection states.

- [x] **Step 1: Start the feedback console**

Use the existing workflow for opening the FixThis feedback console. If using the MCP tool, open the console for the sample app.

- [x] **Step 2: Verify no-device state**

With no selected device, confirm:

```text
[ ○ No device  No device ▾ ] [ ↻ ] [ Clear selection ]
```

Expected behavior:

- live preview polling does not run without a selected device
- saved sessions remain visible
- raw ADB serial is not visible in the topbar

- [x] **Step 3: Verify connected state**

Select a ready device such as `SM_G986N`.

Expected visible state:

```text
[ ● SM_G986N  Connected ▾ ] [ ↻ ] [ Clear selection ]
```

Expected behavior:

- state briefly shows `Connecting` during selection
- after success, state shows `Connected`
- the topbar does not show `Selected adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp`

- [x] **Step 4: Verify refresh**

Click the `↻` button.

Expected behavior:

- tooltip/accessibility copy is `Refresh devices`
- active connected device remains selected when still ready
- if the selected device becomes unavailable, UI changes to `Unavailable`

- [x] **Step 5: Verify clear selection**

Click `Clear selection`.

Expected behavior:

- UI shows `No device`
- FixThis active selection is cleared
- physical USB/Wi-Fi ADB connection remains intact
- subsequent device refresh can rediscover the same device

---

### Task 8: Final Verification And Commit

**Files:**
- Commit source/test changes only after verification passes.

- [x] **Step 1: Run full MCP module tests**

Run:

```bash
./gradlew :fixthis-mcp:test
```

Expected result: pass.

- [x] **Step 2: Review diff**

Run:

```bash
git diff -- fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/console/FeedbackConsoleAssets.kt fixthis-mcp/src/test/kotlin/io/github/fixthis/mcp/console/FeedbackConsoleServerTest.kt
```

Expected result:

- device UI copy matches `Device Connection Control Design`
- raw serial is not used in normal visible status text
- `Disconnect` visible text is gone from the device control
- `Refresh devices` accessibility copy is present
- unrelated Option A annotation/history work is not bundled into this change

- [x] **Step 3: Commit**

Run:

```bash
git add fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/console/FeedbackConsoleAssets.kt fixthis-mcp/src/test/kotlin/io/github/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "fix: clarify feedback console device connection control"
```

Expected result: one commit containing only the device connection control implementation and tests.

---

## Self-Review

- Spec coverage: The plan covers short device labels, four user-facing states, refresh copy, clear-selection copy, topbar raw-serial removal, disabled unavailable devices, and existing API behavior preservation.
- Placeholder scan: No placeholder markers remain. Each code-changing step includes the exact snippet or replacement target.
- Type consistency: The plan uses existing `ConsoleDevice` fields and existing endpoint names. New browser helpers are consistently named `DeviceUiState`, `DeviceStateCopy`, `shortenDeviceSerial`, `deviceLabel`, `deviceDetail`, `deviceOptionLabel`, `setDeviceUiState`, and `deviceBySerial`.
