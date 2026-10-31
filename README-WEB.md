# NexaCloud web build

## Run locally on Windows

From this folder:

```powershell
py -m http.server 8080 --bind 127.0.0.1
```

Open <http://127.0.0.1:8080/>. Do not open `index.html` using `file://`; the service worker and browser security model require HTTP(S). `start-web.ps1` starts the same server.

The web build provides the NexaCloud dashboard, Cloud, Files, Transfers and Settings surfaces, local metadata storage, file selection, queue UI, theme controls and PWA metadata. Fresh installs start empty and show no invented Telegram quota.

Browser JavaScript cannot safely host a Telegram user-account TDLib session by itself. Phone/OTP/2FA/QR login, reliable background backup and encrypted native session storage require the Android TDLib build or a secured backend. Bot API settings, when used, must be supplied by the user and should only be used on trusted HTTPS hosting.

## PWA install

Serve the folder over HTTPS (or localhost), open it in Chrome/Edge, then choose **Install app** / **Add to Home screen**. The manifest is branded NexaCloud and the service worker provides the offline shell.
