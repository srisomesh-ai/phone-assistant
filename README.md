# Somesh Assistant (personal Android app)

A private voice assistant for Somesh's own phones (Vivo X Fold 3 "Bangru", Vivo V29 "Raja", Lenovo tab).
Say the phone's name and give a command, in Telugu, English or mixed. It replies out loud.
Not for the Play Store. Installed directly as an APK.

## What it does

- **Always listening** for "Hey Bangru" / "Hey Raja", even with the screen off while on charge.
- **Answers on the lock screen** without unlocking: time, date, battery, read notifications, set alarms and timers, flashlight, and chat (on-phone AI).
- **When a command needs the phone open** (camera, WhatsApp, other apps), it wakes the screen and you unlock with **face, fingerprint or PIN**; then it finishes the command.
- **Commands:** open any app, camera / selfie, call a contact, send a WhatsApp message you dictate, Google / YouTube search, alarms, timers, torch, lock the phone.
- **On-phone AI chat** (offline) for anything else, once you add a small Gemma model file.

### Not included (by design)
The app does **not** store your PIN or type it in for you. Unlocking is always face / fingerprint / PIN, or Android's own Smart Lock "Trusted place" for the table phones.

## How it's built

- Native Android (Kotlin), min Android 8, arm64.
- Offline wake word + offline English speech: **Vosk** (model auto-downloaded once, ~40 MB).
- Telugu/English commands: the phone's built-in speech recognition (Telugu needs internet), with offline English as fallback.
- Speaks back with the phone's Text-to-Speech (Telugu + English).
- On-phone AI: **MediaPipe LLM Inference** running Gemma 3 1B.

## Install

1. Open the repo's **Releases → latest** on the phone's browser and download `PhoneAssistant.apk`.
2. Install it (allow "install unknown apps" for the browser once).
3. Open the app and finish the **one-time setup** checklist:
   - Microphone, Wake word model (downloads now), Pop-up over lock screen, Battery not restricted.
   - Optional: Read notifications, WhatsApp auto-send.
   - On Vivo: allow background + auto-start (the app shows the exact steps).
4. Set the **wake name** per phone in Settings (Bangru on the Fold, Raja on the V29), and use **Teach wake word**.
5. Tap **Start listening**.

## Lock-screen / table use

- Keep the phone **on charge** on the table.
- For full voice control without touching the phone, turn on **Settings → Security → Smart Lock → Trusted places** (Vivo may call it Extend Unlock) and add your office/home. Inside that place, after you unlock once, the phone stays unlocked and every command runs fully.
- Face unlock also works with a glance when a command needs the phone open.

## On-phone AI model (optional, for chat)

1. On the phone browser, download a Gemma 3 1B `.task` file from the LiteRT community page on Hugging Face.
2. App → gear → **On-phone AI model → Pick model file**.
Without it, all the fixed commands still work; only free chat needs the model (or, later, the home AI).

## Building

Pushing to `main` builds the signed APK with GitHub Actions and publishes it to the `latest` release.
Locally: `gradle assembleRelease` (output in `app/build/outputs/apk/release/`).

## Next step

Connect this app to Somesh's home voice assistant so replies come back in his **cloned voice** and chat is smarter than a small on-phone model.
