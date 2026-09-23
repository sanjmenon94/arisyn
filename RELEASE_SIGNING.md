# Release signing and updates

The Android release key is deliberately stored outside this repository at:

`C:\Users\Admin\.training\release\training-release.jks`

The local Gradle-only signing configuration is stored beside it at:

`C:\Users\Admin\.training\release\signing.properties`

Neither file is tracked by Git. Keep both files private. The keystore is the identity Android uses to permit in-place updates; losing it means future releases cannot update an installed copy of Training.

## Backup

Copy both files together into an encrypted archive or password manager attachment, then store that backup separately (for example, an encrypted external drive and a trusted cloud vault). Do not commit either file, paste either file into chat, or rename the `keyAlias`. Test a backup by restoring both files to the paths above and running `assembleRelease` before relying on it.

To create an update later, keep `applicationId` as `com.training.app`, increase `versionCode`, and build the release variant. The result will be at `app/build/outputs/apk/release/app-release.apk`.
