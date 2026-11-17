# Releasing on Google Play

PluralWare's Play app is `org.foggyden.pluralware`: the phone and watch apps are one listing, with
a bundle for each. Testers get both from Play, on the phone and on the watch, and updates arrive
on their own. GitHub releases continue alongside, signed with the same key, so either can update
the other.

## Each release

The release workflow attaches `play-mobile-X.Y.Z.aab` and `play-wear-X.Y.Z.aab` to the GitHub
release. In the Play Console, under **Test and release → Testing → Internal testing**, create a
release and upload both. The watch's version code is always one past the phone's, as Play
requires for two bundles in one app.

## Once, to set up

1. **Create the app**: name PluralWare, app (not game), free.
2. **App signing**: choose to use the existing release key (the one in the
   `RELEASE_KEYSTORE_BASE64` secret), exported with the Console's encryption tool, rather than
   one Google makes. Otherwise Play's builds and the GitHub ones can't update each other.
3. **Wear OS**: under **Advanced settings → Form factors**, add Wear OS.
4. **Internal testing**: add a testers list (Google account emails, up to 100) and share the
   opt-in link it gives. A tester opens it once while signed in to the same Google account as
   their phone and watch.
5. **App content** declarations:
   - Privacy policy: `https://github.com/mishan/pluralware/blob/main/PRIVACY.md`.
   - Ads: none. Target audience: 18 and over is the simplest; PluralKit's own rules apply.
   - Data safety: no data goes to the developer. The PluralKit token and system data go to
     PluralKit, encrypted in transit, at the user's request, which is how a client of the user's
     own account works; nothing is sold or used for ads. Review Google's definitions before
     answering, since the answers are the developer's to give.
   - App access: the app needs a PluralKit token. Explain that one comes from `pk;token` in a
     DM with PluralKit, or provide a test system's token.
6. **Store listing**: short and full description, the README's screenshots
   (`docs/screenshots`), a 512 × 512 icon and a 1024 × 500 feature graphic.

New personal developer accounts must run a closed test with 12 testers for 14 days before
releasing to everyone. Internal testing has no such requirement.
