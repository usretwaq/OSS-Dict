# Personal build

`.github/workflows/build-apk.yml` builds this fork's app ("OSS Dict+") on every push to a
`feat/**` branch, runs it on an emulator and then publishes the APK as a GitHub release.

## Emulator run

`smoke/smoke.py` installs the built APKs on an emulator and walks through bookmark folders
and notes from the outside, with adb: it looks words up in a small test dictionary, files
them, renames and deletes folders, restarts the app. The release is only published when
every expected element showed up and the app did not crash.

The screenshots and the texts on screen after each step are pushed to the `ci-smoke`
branch, the logs of a build that failed to the `ci-logs` branch. Both branches hold the
last run only.

## Signing key

`personal-build.keystore` (password `ossdict-plus`, alias `ossdictplus`) signs those APKs.
Android only installs an update over an app when both are signed with the same key, so the
key has to stay the same from one build to the next; keeping it in the repository is what
makes that work without configuring any secret.

The key is therefore public. It only guarantees that updates install over each other, it
says nothing about who built an APK: install OSS Dict+ from this repository's own releases
page only.

Certificate SHA-256:
`E8:29:74:9B:2A:43:10:03:D6:DC:22:0D:BC:25:C8:5A:E9:DF:7B:84:D4:64:8B:43:4C:43:BE:40:0F:01:DE:31`
