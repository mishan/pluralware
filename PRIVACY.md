# PluralWare privacy policy

PluralWare is a phone and Wear OS app for [PluralKit](https://pluralkit.me) systems, made by
Misha Nasledov. It has no accounts, ads or analytics, and
sends nothing to its developer.

## What it handles, and where it goes

- **Your PluralKit token** is stored encrypted on your phone, and on your watch if you have one.
  It goes from your phone to your watch and to PluralKit, and nowhere else.
- **Your system's data** (members, switches) is read from and written to PluralKit, at your
  request, over HTTPS. The apps keep a little of it on the device to show it quickly: the members
  list, and the current fronters for the widget, complication and tile.
- **Friend notifications**, if you share switches, name only the members you choose; anyone else
  in front shows as "someone". Sharing tells friends who's fronting even if your front is private
  in PluralKit. Only the friends you add hear anything.
  - Private notifications are end-to-end encrypted. The push service that delivers them can't
    read them.
  - Simple (ntfy) notifications can be read by whoever runs the ntfy server.
  - Discord channel posts are read by Discord and by everyone in the channel, and they stay
    there. The webhook link is stored encrypted on your devices.
- **A relay**, if you host one, holds your friends' follow codes, any Discord webhooks you added,
  and the names of the members you chose to share. It never holds your PluralKit token. It runs
  on a server you control.
- **Following friends** uses UnifiedPush and the distributor app you choose (such as ntfy).

Nothing is backed up or transferred to a new device: the encryption key never leaves the device.
Disconnecting in the app's settings deletes your token and sharing settings; uninstalling deletes
everything it stored.

## Contact

Questions go to the [issue tracker](https://github.com/mishan/pluralware/issues).
