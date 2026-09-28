# Prepare before losing the phone display

S10 Console targets **Galaxy S10 SM-G973F, beyond1, Android 12 / API 31**, on a
Linux PC. A working battery, bootable phone, working USB data path, enabled USB
debugging, and prior trust of this PC are prerequisites. The software does not
bypass a lock, RSA prompt, secure surface, or encryption.

**Recorded target-phone gap:** the owner reported a black/not-visible lock screen
in the PC viewer. PC-based normal unlock has not been demonstrated for this S10,
even though unlocked screen control works. Do not mark dead-screen readiness
complete until a visible, legitimate owner-unlock path is confirmed.

## One-time preparation

1. On the working phone, enable Developer options (tap Build number seven times
   in Software information, then use your normal unlock credential) and choose
   USB debugging. Connect a known-good **data** cable, not a charge-only cable.
2. Open S10 Console. Click **Refresh devices** to read local USB descriptors and
   explicitly select your Samsung USB serial. This discovery does not use ADB
   or claim the model is verified. If its local ADB server is absent, review
   **Prepare > Start local ADB**. This starts the ordinary localhost server,
   never replaces or kills an existing server. Accept the phone's RSA prompt for this PC;
   select **Always allow from this computer** only if you trust and protect it.
3. With the correct USB phone explicitly selected, click **Verify
   selected USB S10**. Review the hardware model and serial before enrollment.
   The app does not choose the only connected device automatically. Other phones,
   network endpoints, and unverified identities cannot be enrolled.
4. Click **Enroll this verified S10**. Its identity lives in the private XDG
   configuration, not app source. Unplugging/replugging or changing USB ports
   does not change that durable identity.
5. On **Control**, select **USB Balanced** and start the viewer. A separate
   native scrcpy window opens. Its temporary server runs through already-authorized
   ADB; no installed companion or fresh MediaProjection approval is needed.
   Nothing in the app starts capture at login or simply by opening the console.
6. Test mouse clicks, long press, scrolling, typing, Back, Home and Recents.
   The default physical UHID keyboard may need **Alt+K** in the viewer to configure
   its layout. If UHID fails, save a profile using the **sdk** keyboard; SDK typing
   has character/IME limitations. Mouse input remains SDK touch mapping.
7. Hide the physical display **without disabling it**, and avoid phone touch.
   Start **Prepare > reconnect observation**. Start control if not already active,
   unplug USB, wait for the missing state, and replug. Check both the actual live
   image and input. Repeat twice. The observation counter only proves presence
   transitions of the verified phone; it does not prove capture/control success.
8. Record success only for scenarios you actually observed. Close the viewer,
   reopen it, and restart the console. Reconnect must work without a phone prompt.
   Rehearse restart boundaries while a working display remains a recovery option.

## Keep this PC trusted without disabling your phone's security

Android may expire unused authorizations; Samsung firmware exposes different
developer settings. Review the phone's authorization timeout option explicitly
and understand its security tradeoff before changing it. The console does **not**
change that setting, stay-awake, screen timeout, lock method, Wi-Fi, or firmware.
Do not disable the screen lock as a routine fix.

The usual ADB private key is `~/.android/adbkey`. Protect this PC's account,
disk, and backups. The key check inspects private-key **metadata only** and hashes
the public key. It does not read the private key. Its SHA-256 identifier is for
tracking the local public key, not necessarily the same format as the phone's RSA
prompt. A shared server may have loaded an older/different key, so a successful
current connection does not prove the disk key will work after restart.

**Optional backup is a separate owner decision.** The app does not export keys.
If you choose a backup, use your trusted encrypted backup tool and an explicitly
chosen private destination for the normal-user ADB credentials. Treat both the
private key and wireless pairing trust files as credentials; do not post them,
put them in source control, or email them. Check your ADB version's stored trust
files rather than assuming that copying only `adbkey.pub` preserves trust. A
public key alone cannot restore access. Rehearse recovery on a trusted PC while
the display works; never overwrite an active server's credentials blindly.

## What happens after reboot

**PC restart:** start the normal-user ADB server and console again. It should use
the same protected identity, but test this yourself before relying on it. This app
never kills a server owned by another program.

**Phone restart:** Android's credential-encrypted storage is unavailable before
first normal unlock. Whether authorized ADB, capture, and input are available at
that stage is firmware-dependent. Enter your known PIN/password yourself in the
normal interface only where OS policy permits. The app stores no credentials and
does not promise that every lock screen or protected prompt can be controlled.
Biometric unlock alone may not suffice after a reboot.

The production console does **not** reject an authorized ADB connection merely
because the phone is locked. Normal viewer startup and Wake remain available so
the owner can use the ordinary lock screen through the PC where Samsung/Android
permits capture and input. The stricter unlocked/foreground check belongs only
to the disposable automated clipboard-test fixture, to prevent test keystrokes
from reaching a PIN screen or an unrelated app. It is not a production unlock
requirement and does not disable the phone's lock.

Only you should initiate phone/PC reboot tests. Keep the screen/HDMI recovery path
available. Record observations, not assumptions.

## Optional same-LAN Wi-Fi

USB remains the preferred recovery route. On the working S10, explicitly enable
**Wireless debugging** on a trusted network and choose **Pair using pairing code**.
Both devices must be on the same LAN.

1. Stop the viewer, then enter the phone's **pairing** address and six-digit code
   on Tools. The field is cleared on submission. The code goes through stdin only
   after the ADB pairing prompt; it is not a process argument or saved setting.
2. After pairing, discover **TLS connection services**. The connection port is
   different from the pairing port. Compare the discovered address to the phone
   and explicitly select its service.
3. Connect and verify that service. The app requires an ADB TLS-upgrade response,
   then delegates authenticated TLS to the paired ADB backend. It verifies the
   phone serial/model against USB enrollment before binding the service.
4. Select the Wi-Fi profile and start control. When a bound service remains
   discoverable, its changed port can be resolved again. Missing/changed service
   identity, lost pairing, reboot, disabled wireless debugging, LAN isolation,
   or blocked mDNS may require USB recovery and explicit rebinding.

Only RFC1918 IPv4 LAN services are supported in this version. IPv6-only networks
need USB. No legacy `adb tcpip 5555`, scrcpy `--tcpip`, WAN listeners, or automatic
plaintext fallback exists. A non-5555 port is **not** proof of encryption.
ADB owns wireless trust; the console's saved service name is not a credential.
Never treat an mDNS advertisement by itself as proof of phone identity.

Stopping control leaves the shared ADB connection intact. It does not revoke PC
trust or stop other programs using that server. Android 17's Wi-Fi 2.0 automatic
reconnect behavior must not be assumed on this Android 12 S10.

## If the screen becomes unusable

Use the prepared cable/PC, open the console, and start USB control. No phone touch
should be needed for the **scenarios you previously demonstrated**.

If authorization is lost, do not reset the phone in an attempt to recover data.
Try a rehearsed USB-C HDMI/DeX monitor plus keyboard/mouse using a compatible
powered hub. Samsung supports wired HDMI/DeX on the S10 series; first use can
require Start/Continue prompts and normal unlock. External input can enter a
known PIN in supported states, but is not a security bypass.

This is independent HDMI output, **not DeX-for-PC on Linux**. A PC/laptop HDMI
connector usually outputs video and cannot display the phone's HDMI signal.
OTG keyboard/mouse alone does not give you a picture or authorize ADB. If both
authorized control and external display fail, use an owner-authorized display/USB
repair service without surrendering credentials unnecessarily.

An HDMI/hub or USB replug rehearsal can interrupt an existing tethered internet
connection. Schedule that separately; do not unplug or reconfigure the active
hotspot/tethering setup as part of the current non-disruptive checks.

Official references: [Android ADB](https://developer.android.com/tools/adb),
[scrcpy prerequisites](https://github.com/Genymobile/scrcpy/blob/v4.1/README.md),
[Samsung S10 DeX compatibility / first use](https://www.samsung.com/us/support/answer/ANS10001955/),
[Samsung broken-screen recovery](https://www.samsung.com/us/support/answer/ANS10002919/).
