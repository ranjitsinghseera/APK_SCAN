# Chassis Scan (Android)

Scans the QR / Data Matrix label, picks the chassis number from it, reads the
number stamped on the frame with on-device OCR, and compares the two.
Everything runs on the phone (Google ML Kit, offline). No server or API key.

## Get the APK without installing anything (GitHub)

1. Create a new repository on github.com (private is fine).
2. Upload all files from this folder, keeping the folders (`.github`, `app`, …).
   Easiest: on the repo page choose **Add file > Upload files** and drag the
   unzipped folder contents in. Make sure `.github/workflows/build-apk.yml` is included.
3. Open the **Actions** tab. The "Build APK" run starts by itself (about 5 minutes).
4. When it shows a green tick, open the run and download **ChassisScan-apk**
   at the bottom. Unzip it to get `app-debug.apk`.
5. Copy the APK to the phone, open it, and allow "Install unknown apps" when asked.

## Or build in Android Studio

Open this folder in Android Studio (Ladybug or newer), let it sync, then
**Build > Build App Bundle(s) / APK(s) > Build APK(s)**. If it asks to create
a Gradle wrapper, accept.

## Using it

- **QR label** mode reads automatically. If the QR holds several fields, they
  appear as chips; tap the one that is the chassis number.
- **Stamped number** mode: fit only the number inside the yellow box, tap the
  preview to focus, light it from the side (or use *Light on*), tap
  **Read number**. It reads 5 frames and takes the most common result.
- Records are stored on the phone. **Share CSV** sends them by WhatsApp, email, etc.

## Where to change things

- Chassis field names in the QR: `KEY_RX` in `Chassis.kt`
- OCR picking rules (VIN fix-ups, non-17-character formats): `pickFromOcr` in `Chassis.kt`
- Box size for stamped numbers: `STAMP_W` / `STAMP_H` in `GuideView.kt`
