# Drive Sequencer (Android)

Music that follows your driving. GPS speed sets the tempo, brightness and which instruments play; accelerating hard adds fills, braking muffles the sound, and stopping at a red light brings it down to a calm idle. Like the music in Tanks!, instruments join one at a time as the energy rises and drop out as it falls.

- **4 presets:** Synthwave, Marching band, 8-bit, Lo-fi
- **My song mode:** loops a song from your phone and speeds it up or slows it down with you (you can keep the pitch or let it shift like a record)
- **Plays in the background**, with the screen off or behind Google Maps. Stop it from the notification.
- **Turns itself down** while your sat-nav speaks and pauses for phone calls
- **Simulator and demo drive** for trying it at home

Needs Android 8.0 or newer.

## ⬇ Download

**[Download DriveSequencer.apk](https://github.com/RoaringNoodle/DynamicMusicSequencer/releases/latest/download/DriveSequencer.apk)** (the newest build), then open it on your phone. See *Installing on the phone* below.

---

## Getting the APK (the file you install on the phone)

### Option A: build it on GitHub (no installs, free)

1. Sign in at github.com and make a **new repository** (it can be private).
2. On the new repo's page, click **"uploading an existing file"** and drag in **everything inside this folder**, including the hidden `.github` folder. Then commit.
   - On a Mac, Finder hides `.github`; press **Cmd + Shift + .** to show it.
   - If `.github` doesn't upload: open the **Actions** tab, choose **"set up a workflow yourself"**, paste in the contents of `.github/workflows/build-apk.yml`, and commit.
3. Open the **Actions** tab. A "Build APK" run starts on its own and takes about 3–5 minutes.
4. When it shows a green tick, open the run and download **DriveSequencer-apk** at the bottom. It's a zip with `app-debug.apk` inside.

### Option B: Android Studio

1. Install Android Studio from developer.android.com/studio.
2. **File → Open**, then choose this folder. Wait for "Gradle sync" to finish; the first time, it downloads what it needs.
3. To install straight to your phone: turn on **Developer options → USB debugging** on the phone, plug it in, and press the green **Run ▶** button.
   To make an APK file instead: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.

## Installing on the phone

1. Copy `app-debug.apk` to the phone (or download it there) and tap it.
2. Android will ask you to allow installs from that app (Files, Chrome, etc.). Allow it, then install.
3. Play Protect may warn that the app is from an unknown developer. That's because you built it yourself. Choose **Install anyway**.

## First run

- Tap **Try simulator** first, then **Play a demo drive** to hear how it reacts.
- **Drive with GPS** asks for location. Choose **Precise** and **While using the app**.
- Allow notifications so the Stop button shows in the notification shade.
- **Samsung, Xiaomi, OnePlus and some other phones** close background apps aggressively. If the music stops when the screen is off, go to **Settings → Apps → Drive Sequencer → Battery** and choose **Unrestricted**.

Set it up before you drive off; nothing needs touching while you're moving.

---

## Where things are

| File | What it does |
|---|---|
| `app/src/main/java/com/drivesequencer/Synth.java` | The music engine: how speed becomes energy, the step sequencer, the 4 presets, the synth voices and effects. Plain Java, so it also runs on a PC. |
| `DriveService.java` | Background service: GPS, audio output, the song player, volume ducking, the notification. |
| `MainActivity.java` | The main screen and its controls. |
| `GaugeView.java` | The speedometer, step lights and instrument labels. |

### Making your own preset

Each preset in `Synth.java` sets these:

- a name and description
- a tempo range (`bpmLo`, `bpmHi`)
- a brightness range (`cutLo`, `cutHi`, in Hz)
- `swing` (0 = straight)
- 4 chords and their bass notes, as MIDI numbers (60 = middle C)
- the instruments, and the energy (0–1) at which each one joins
- `step(...)`, called 16 times per bar, which says what plays on each 16th note

To make a new one, copy an existing preset block, change it, and add it to the `PRESETS` list. It shows up on the screen automatically.

Tweaks worth knowing:

- **How hard acceleration hits:** `acc / 8` in `control()`. Lower the 8 to make it react more.
- **When instruments are allowed to change:** they join or leave only on half-bars (steps 0 and 8), and must drop 0.06 below their energy point before leaving. That's the `eBar` logic in `block()`.
