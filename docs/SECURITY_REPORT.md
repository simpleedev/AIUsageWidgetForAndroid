# Security hardening report

Assessment date: October 5, 2026. Baseline: signed v1.1.8 (code 21). Candidate: v1.1.9 (code 22).

This review covers session storage, login WebViews, native HTTP requests, widget refresh entry
points, diagnostics, upgrade behavior, and the signed build workflow. It is a source review and
targeted automated/device validation, not a formal penetration test or a guarantee against every
attack.

## Previous and new behavior

| Area | v1.1.8 behavior | v1.1.9 behavior |
| --- | --- | --- |
| Saved sessions | Cookies and ChatGPT bearer tokens stored as ordinary private preference strings. | A shared SessionStore encrypts each service record using AES-256-GCM and a device-bound Android Keystore key. Every encryption uses a fresh IV; service and session revision are authenticated. |
| Upgrade migration | No encrypted migration. | Encrypt, synchronously save, and verify the record before deleting its plaintext predecessor. Restarted migrations clean up leftover plaintext. A failed migration does not use plaintext credentials for network requests and retains the old record for a later retry. |
| Damaged records or missing key | No authenticated corruption detection. | Reject the encrypted record, remove it and any legacy copy, invalidate the session revision, and require a usable sign-in. No fallback to an older plaintext session. |
| Backups and transfers | Manifest enabled default backups, which can include preferences and WebView storage. | Backups disabled, plus explicit exclusions for every credential-protected app data domain in both legacy backup XML and Android 12+ cloud/device-transfer rules. |
| Release WebView debugging | Enabled unconditionally. A debug socket was observed on the connected OnePlus running v1.1.8. | Enabled only when the app itself is debuggable. Signed release builds disable it. |
| Login navigation | Main pages/popups could navigate without an app-specific hostname boundary. | Main-frame navigation, redirects, and popups are restricted to explicit HTTPS service and identity-provider hosts. User-info URLs, unexpected ports, lookalike domains, local files, and unsupported schemes are rejected. |
| Login WebView settings | Mixed content allowed; automatic popups enabled; local file/content access not explicitly disabled. | Mixed content and local file/content access disabled, Safe Browsing enabled, and popups require a user gesture. JavaScript and third-party cookies remain enabled because authentication requires them. |
| ChatGPT token extraction | Native JavaScript bridge exposed to every frame, relative session fetch on the current page, plus bearer-header interception using a substring hostname test. | No native JavaScript bridge or bearer-header interception. One asynchronous native request at a time targets the fixed HTTPS ChatGPT session endpoint using that origin's cookies. Pending login checks are canceled and identity-checked when login ends or restarts. |
| Native HTTP | Automatic redirects enabled; response bodies unbounded. | Automatic redirects disabled for credential-bearing calls, fixed service URLs, validated organization path component, timeouts, and a 1 MiB response cap enforced before parsing, including chunked responses. Default TLS certificate/hostname verification remains in use. |
| Browser credential copies | Service cookies remained in the browser after capture. | Known service/authentication cookies, origin web storage, browser history, and cache are cleaned after encrypted capture; leftover service cookies are cleaned when opening an already-connected app. Google cookies remain available for the current embedded sign-in flow. |
| Widget refresh broadcasts | Exported widget providers processed custom refresh actions from arbitrary senders. | Custom refresh handling removed from exported widget providers. Explicit immutable PendingIntents target the non-exported QuotaNotificationReceiver. Normal widget lifecycle rendering remains available. |
| Logout/re-login races | A running worker could write a refreshed token or readings after the session was cleared or replaced. | Revision checks under the shared session lock reject stale credential, reading, error, and notification writes. Logout clears that service's quota readings; the other service's encrypted session remains intact. |
| Diagnostics | Webpage console output, raw HTTP error snippets, and exception messages/stacks could enter logs. | Console output discarded, HTTP diagnostics contain status codes only, exception details reduced to type, common credential patterns redacted, and old app logs cleared once on upgrade. |
| Notification permission changes | Custom permission checks did not protect every notification operation from revocation races. | A checked notification helper also handles SecurityException if permission changes between check and use. |
| Build validation | Signed builds verified the release certificate, without security regression tests. | Unit tests, release lint, and Android instrumentation must succeed before signing. Workflow actions are pinned to reviewed commit identifiers. The existing release certificate check remains mandatory. |

## Verification

Validation is in progress. Results and exact build identifiers will be recorded before delivery.

Automated cases cover hostile URLs, authenticated-encryption tampering, nonce uniqueness,
wrong service/revision/key, redirect credential isolation, oversized responses, log redaction,
migration/restart/failure, logout/re-login races, cross-service isolation, real Android Keystore
operations, WebView settings and missing bridge, private receiver/immutable PendingIntent metadata,
backup exclusions, and token parsing.

The debug application uses a separate `.securitytest` application ID, so synthetic security tests
cannot modify the signed application's sessions or settings. Device testing is authorized for the
connected OnePlus CPH2551 (Android API 36).

## Remaining limits and operational effects

- Google sign-in still uses the app's WebViews and the existing browser user-agent compatibility
  change. External browsers do not expose their cookies to this app. This hardening does not resolve
  Google's embedded-OAuth restrictions or service terms for polling website usage endpoints.
- Third-party identity-provider sessions (especially Google) are still managed by WebView. The app's
  Keystore encryption covers its saved worker credentials, not the entire WebView cookie database.
  Browser cleanup covers known service origins and root-path cookie variants; it is not a proof that
  every possible website cookie/path was erased. Full logout additionally clears all browser cookies,
  web storage, and cache locally.
- Encryption at rest does not protect credentials from a compromised app process, a rooted device,
  malicious accessibility software, or a compromised service website. Keys are not biometric-gated
  because unattended quota refresh must continue. Hardware-backed key availability depends on the device.
- Previously created OS backups and previously shared logs are not retroactively deleted by this update.
  Disabled backups/device transfers mean a reinstall or new phone needs sign-in again. Logs from the
  previous app version are deliberately cleared during the upgrade.
- Local logout removes this app's session material; it does not revoke every session on the provider's
  servers. An already-started network request may finish, but its result cannot restore a replaced or
  logged-out session.
- Login hostname restrictions can need maintenance when a provider changes authentication hosts.
  Unknown destinations are blocked rather than automatically trusted.
- The target SDK and general library versions remain unchanged by this authentication hardening.
  Play Store readiness, a dependency vulnerability audit, and a broader Android SDK/toolchain upgrade
  remain separate work. Non-blocking lint warnings must not be interpreted as a vulnerability scan.

## References

- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [Android backup and transfer rules](https://developer.android.com/identity/data/autobackup)
- [WebView native bridge risks](https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges)
- [Safe URL loading](https://developer.android.com/privacy-and-security/risks/unsafe-uri-loading)
- [WebView debugging](https://developer.android.com/reference/android/webkit/WebView#setWebContentsDebuggingEnabled(boolean))

