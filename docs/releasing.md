# Releasing

Releases are GitHub Releases, built and signed by `.github/workflows/release.yml`
when a `vX.Y.Z` tag is pushed. Each one carries the phone APK, the watch APK and
a `SHA256SUMS` file. Phone users follow them with Obtainium; watch users install
the watch APK themselves (see the README).

## The release key

Both apps are signed with one key. That's required, not tidy: the Wearable Data
Layer only connects a phone app and a watch app with the same package name *and*
the same signing certificate.

Android only installs an update signed with the key the app was installed with.
**If the key is lost, nobody can update**: every user has to uninstall, losing
their pairing and settings. Keep an offline backup (a password manager, or an
encrypted drive) of the keystore file and its password.

Create it once:

```sh
keytool -genkeypair -v -keystore pluralware-release.jks -alias pluralware \
  -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12
```

The keystore is PKCS12, so the key's password is the keystore's password.

If PluralWare later goes to Google Play, enroll this same key in Play App
Signing, so Play builds and GitHub builds stay interchangeable.

## GitHub secrets

In the repository's **Settings → Secrets and variables → Actions**, add:

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 pluralware-release.jks` |
| `RELEASE_KEYSTORE_PASSWORD` | the keystore password |
| `RELEASE_KEY_ALIAS` | `pluralware` |

## Cutting a release

1. Set `pluralware.version` in `gradle.properties` to the new `X.Y.Z` and
   commit it. Both apps take their version from it: the phone's versionCode is
   `(X*10000 + Y*100 + Z) * 10` and the watch's is one more, so the two never
   collide if they ever share a Play listing.
2. Tag the commit and push the tag:

   ```sh
   git tag vX.Y.Z
   git push origin vX.Y.Z
   ```

The workflow refuses a tag that doesn't match `pluralware.version`, refuses to
run without the key, and checks that both APKs carry the same certificate. The
release notes list that certificate's SHA-256 fingerprint.

## Building a signed release locally

Set the same values as environment variables, with an absolute keystore path:

```sh
export PLURALWARE_KEYSTORE=/path/to/pluralware-release.jks
export PLURALWARE_KEYSTORE_PASSWORD=...
export PLURALWARE_KEY_ALIAS=pluralware
./gradlew :mobile:assembleRelease :wear:assembleRelease
```

Without them, release builds come out unsigned.
