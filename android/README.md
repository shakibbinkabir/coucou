# Coucou for Android

> **Archived, not pursued.** Written on 9 and 10 October 2026, then dropped: Claude's own app already does this with remote control. It builds, and was exercised on an emulator (every screen, an approval from the app and from a notification, a question, the widgets' drawing, and a real Claude Code session through the hooks). On a real phone only the install, the setup guide and the link over Wi-Fi were checked; the sounds, the fingerprint prompt and an approval from a live interactive session were not.

The Android version of [Coucou on iPhone](../docs/IPHONE.md): your agent sessions live, permission requests you can answer from a notification, Claude's questions, the last turn of each session with its diffs, widgets, and Mochi.

It is a native app in Kotlin and Jetpack Compose, with the same screens, wording and Mochi as the iPhone app (`NotchBuddy/Sources/Phone`). Mochi is drawn in code by a port of the same engine (`BotEngine`), no image.

## How it is linked

The iPhone gets its sessions from the Mac through the user's iCloud. Android has no iCloud, so here the agent's hooks send their events **straight to the phone, over your Wi-Fi**:

```
Claude Code ── hook (curl) ──▶ http://<phone>:47821/hook ──▶ sessions, turns, notifications
                               ◀── Allow / Deny, on the same connection
```

- The phone listens on your local network (port 47821). Each event is the hook's own JSON, sent as it is.
- Only a permission request waits for an answer: its connection stays open until you tap Allow or Deny, and the answer goes back on it. If nobody answers in 108 s, or the question is answered in the terminal, the request is dropped and the terminal carries on as if Coucou were not there.
- **The agent is never blocked.** Every other event is sent in the background (`"async": true`), and curl gives up after 0.4 s when the phone is away.
- Whoever holds the link's token may send sessions to the phone. Nothing a sender says can allow anything: only a tap on the phone does, after your fingerprint or screen lock.
- No server, no account, no analytics. Sessions are kept on the phone only.

## Set it up

1. Build and install the app (below), open it, allow notifications.
2. Put the phone and the computer on the same Wi-Fi.
3. In the app: the gear → **Your computer** → **Copy the hooks for Claude Code**, and merge that `hooks` block into `~/.claude/settings.json` on the computer.
4. Start Claude Code. The session shows up on the phone.

To check the link from the computer, **Copy a test command** and run it: it prints `{"app":"coucou"}`.

Other agents send the same way with `?agent=<name>` at the end of the address (`…/hook?agent=codex`); they get their own Mochi.

On Windows the hooks run in Git Bash, where `curl` is the real one. Without Git Bash they run in PowerShell: write `curl.exe` there.

## What differs from the iPhone

| | iPhone | Android |
|---|---|---|
| Link | iCloud (CloudKit), from the Mac app | Local Wi-Fi, straight from the agent's hooks. The phone and the computer must be on the same network (or a VPN that joins them) |
| The computer's side | Coucou for Mac publishes | Nothing to install: a `hooks` block in the agent's settings |
| Sessions, steps, last turn, diffs, earlier turns, today's tally | Built by the Mac | Built on the phone from the same hook events |
| Allow / Deny | Face ID | Fingerprint, face or the screen lock (never skipped; a phone with no screen lock cannot allow) |
| Mochi on the Lock Screen (Live Activity) | Started by the Mac when it locks | A permanent notification with Mochi, the most urgent session and Allow / Deny. It is also what keeps the link listening |
| Notifications | Allow, Review, Deny; one button per choice of a question; reply to a finished agent | The same, with three choices at most on a question. No reply (see instructions) |
| Widgets | Solo, Team, List, Lock Screen | Solo, Team, List. A tap opens the most urgent session |
| Send the next instruction | Yes, run by the Mac | Not yet: nothing on the computer takes instructions from the phone, so the field does not show |
| Services (GitHub, Vercel, Stripe…) | Read by the Mac, shown on the iPhone | Not yet: the tab says so |
| Siri, Shortcuts, Spotlight, Focus filter, Control Center, alternate icons | Yes | No |
| With Coucou for Mac, Windows or Linux running too | — | Not tested. Both get every event, so sessions should show on both; a permission request would be asked on both, and how the agent weighs two answers is not something this app controls |

The link is plain HTTP on the local network: a command or a path travels unencrypted between your computer and your phone. Use it on a network you trust.

## Build

Requirements: JDK 17, the Android SDK (platform 36).

```bash
cd android
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # what goes back to an agent (HookReplyTest)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A build installed this way prints the test command to logcat (`adb logcat -s Coucou`).

## Where things are

| File | What |
|---|---|
| `Mochi.kt`, `Ctx2D.kt`, `Anim.kt`, `MochiViews.kt` | Mochi: the engine, the canvas it draws on, the views |
| `HookServer.kt`, `HookReply.kt` | The link: the HTTP server, and what goes back to each agent |
| `Hooks.kt`, `Recorder.kt` | Hook events → sessions and the last turn (the Mac's HookServer, SessionPublisher, TurnRecorder) |
| `Link.kt`, `Models.kt` | What the screens read (the iPhone's PhoneLink and its models) |
| `LinkService.kt` | The foreground service and the notifications |
| `MainActivity.kt`, `AgentsTab.kt`, `SessionDetail.kt`, `About.kt`, `Search.kt`, `Intro.kt` | The screens: tabs, agents, a session with its last turn, settings and setup guide, search, the opening |
| `Style.kt`, `Sym.kt` | The iPhone app's look in Compose, and its symbols |
| `Widgets.kt` | The home screen widgets |
