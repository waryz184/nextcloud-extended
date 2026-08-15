# Release Checklist

## Android Version

Update both values in `app/build.gradle.kts` for every release:

- `versionCode`: increment the previous Play Console value.
- `versionName`: increment the displayed application version.

## Artifact Names

The public release assets must keep the historical names:

- `NextcloudExtended.aab`: signed Android App Bundle for Google Play Console.
- `NextcloudExtended.apk`: signed APK for direct installation and testing.

Do not publish the raw Gradle output names `app-release.aab` or `app-release.apk`.
Rename or copy the generated files before uploading them to GitHub Releases.

## Build and Publish

Build both release artifacts, then upload the renamed files to the matching GitHub
release tag. Verify that the uploaded asset names and SHA-256 hashes match the
local files before considering the release complete.
