# Instant Recorder

Capture a thought without unlocking your phone: **press both volume buttons**, say what you need to do, and get it back as tasks with due dates. Transcription runs entirely on the phone, so your voice never leaves it.

> "Remind me to call mom tomorrow at 5 pm, and book the dentist for next Tuesday."
>
> → **Call mom** · Tomorrow 5:00 PM
> → **Book the dentist** · Tuesday

Built and tested on a Pixel 10 Pro Fold (Android 17). It should work on other Android 12+ phones, but it's untested there.

## Install

1. On your phone, download **[instant-recorder.apk](https://github.com/augustopedroza/instant-recorder/releases/latest/download/instant-recorder.apk)** (the newest build) and open it. Android will ask you to allow your browser to install apps; allow it.
2. Open **Instant Recorder** and follow the "Finish setting up" card:
   - Allow the microphone and notifications.
   - Choose a speech model: **Fast** (148 MB, results in about a second) or **Accurate** (190 MB, better with names and unusual words, a few seconds per note). You can switch later in Settings.
   - Turn on the volume-button trigger. This is an Accessibility service, and because the app didn't come from the Play Store, Android blocks it at first with **"App was denied access"** (on older versions, "Restricted setting"). The unlock option only appears after Android has blocked one attempt, so follow this order:
     1. **Settings → Accessibility → Instant Recorder volume-button trigger** → tap the switch, then dismiss the "denied access" message.
     2. Long-press the Instant Recorder icon → **App info** → **⋮** (top right) → **Allow restricted settings**, and confirm with your PIN.
     3. Go back to **Settings → Accessibility → Instant Recorder volume-button trigger** and switch it on.

     If **Allow restricted settings** never appears, check that Advanced Protection is off (Settings → Security & privacy → Advanced Protection). As a last resort, an app installed over USB with `adb install` isn't restricted.

**Updates:** download the APK again from the link above and install it over the old one.

**Google's developer verification:** Google now requires Android apps to come from identity-verified developers, even when installed outside the Play Store. It has been enforced in Brazil, Indonesia, Singapore and Thailand since September 30, 2026, and is planned worldwide from 2027. If your phone refuses to install the app, that's why. Installing over a USB cable with `adb install instant-recorder.apk` still works.

## Using it

| To | Do this |
|---|---|
| Start recording | Press **both volume buttons together**. Works on the lock screen and with the always-on display. |
| Stop | Press either volume button, tap **Stop** in the notification, or just stop talking (it stops after 8 s of silence). |
| Start without the buttons | Double-tap the back of the phone (Pixel: Settings → System → Gestures → Quick Tap → Open app → **Quick Record**), the Quick Settings tile, or the mic button in the app. |

While recording, a red timer chip shows in the status bar. A few seconds after you stop, a notification lists the tasks it found: tap **Save all**, or open it to edit the tasks, add them to your calendar, or share them to any task app. Saved tasks are in the **Tasks** tab; tap one to change its title, date, category or notes, or use **Add task** to type one in (or tap its mic and say it: "call the dentist tomorrow at 3" fills in the title and date). Swipe a recording or a task to the left to delete it (you can undo). Checking a task off moves it to the **Done** tab, grouped by the day you finished it; uncheck it there to put it back. After you save a recording's tasks, the app offers to delete the recording, since the transcript stays in the tasks' notes; the "tasks saved" notification has a **Delete recording** button too. Each task's **Share** button sends it to Google Tasks, Asana, Trello, Keep or any other app that accepts shared text. Tasks can repeat on chosen weekdays ("water the plants every Monday and Thursday", or the Repeat row in the editor); only the next one is listed, and checking it off creates the one after. Reminders can also be tied to a calendar event: "prepare the slides 2 days before the board meeting" finds the event and follows it if it's rescheduled; say "…before **every** board meeting" (or switch on **Every time**) and checking it off creates the one for the next meeting with that name, even when the calendar keeps them as separate series, and a pop-up says when the next reminder is; if a meeting starts before you check its task off, the task moves on to the next meeting by itself (or use **Before an event…** in the task editor). Each task shows which meeting it is for, such as "1 week before Ward Council on Sun, Nov 8". Reminders arrive as notifications with sound; switch on **Important** for a task and its reminder rings like an alarm, through Do Not Disturb, with a full-screen alarm when the phone is locked.

Single volume presses work exactly as before, including holding a button to change the volume quickly. Everything is adjustable in Settings: the gesture, walkie-talkie mode, silence timeout, maximum length and the speech model.

## Privacy

- Audio is recorded and transcribed **on the phone** with [whisper.cpp](https://github.com/ggml-org/whisper.cpp). The only network use is the one-time speech model download from Hugging Face.
- The Accessibility service only watches the **volume buttons**. It doesn't read the screen or what you type.
- Calendar access (optional, for reminders before events) is read-only and stays on the phone. Settings → Calendars chooses which calendars are searched.
- Recordings and tasks stay in the app's private storage. Uninstalling deletes them.

## Build from source

Requirements: JDK 17, Android SDK platform 37.2, NDK 28.2.13676358, CMake 3.31.5.

```sh
git clone --recurse-submodules https://github.com/augustopedroza/instant-recorder.git
cd instant-recorder
./gradlew :app:testDebugUnitTest   # unit tests
./gradlew :app:installDebug        # build and install on a connected phone
```

Every push to `main` is built by [GitHub Actions](.github/workflows/build.yml). The workflow runs the tests and publishes a signed APK as a [release](https://github.com/augustopedroza/instant-recorder/releases). The build number is the commit count, so each build installs over the previous one.

```
app/src/main/cpp/                 CMake + JNI bridge to whisper.cpp
app/src/main/java/app/instantrecorder/
  recording/                      foreground service: mic → 16 kHz WAV → transcription queue; silence detection
  trigger/                        volume-button Accessibility service (both-buttons gesture), Quick Settings tile
  transcribe/                     whisper.cpp wrapper, model downloads
  tasks/                          task extraction, reminders, calendar/share hand-offs
  ui/                             Compose screens
app/src/debug/                    adb hooks for on-device benchmarks (debug builds only)
third_party/whisper.cpp           git submodule
```

## License

[MIT](LICENSE). whisper.cpp is MIT-licensed, and so are OpenAI's Whisper models.
