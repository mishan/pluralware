# Setting up the relay on Cloudflare

This guide is for anyone, no programming needed. It takes about 20 minutes, once. You'll copy and
paste a few lines into a terminal window; each is given in full.

## What the relay does

Without the relay, PluralWare tells your friends about switches you make **in PluralWare**. With
it, they also hear about switches made anywhere else: `pk;switch` in Discord, the PluralKit
dashboard, other apps. PluralKit tells the relay about every switch, and the relay passes it on.

The relay runs on Cloudflare, for free. It's yours alone: nobody else's switches go through it,
and it never has your PluralKit token, so it can't read or change your system.

## What you need

- A computer (Windows, Mac or Linux). You only need it for setup; afterwards it can be off.
- A free **Cloudflare** account. Sign up at <https://dash.cloudflare.com/sign-up>. The free plan is
  plenty; you don't need to add a card or a website.
- **Node.js**, a free program Cloudflare's tools need. Get the "LTS" version from
  <https://nodejs.org> and install it with the default options.
- PluralWare on your phone, signed in.

## 1. Download the relay

1. Go to <https://github.com/mishan/pluralware>.
2. Click the green **Code** button, then **Download ZIP**.
3. Unzip it. You'll get a folder named `pluralware-main`, with a folder named `relay` inside.

## 2. Open a terminal in the relay folder

A terminal is a window where you type commands. You'll paste every command below into it, then
press Enter.

- **Windows:** open the `relay` folder in File Explorer, right-click an empty part of the window,
  and choose **Open in Terminal**.
- **Mac:** open **Terminal** (in Applications → Utilities). Type `cd` and a space, drag the `relay`
  folder onto the Terminal window, and press Enter.
- **Linux:** right-click the `relay` folder and choose **Open in Terminal**.

To check you're in the right place, paste this and press Enter:

```sh
node --version
```

It should print a version number such as `v22.11.0`. If it says the command isn't found, install
Node.js (see above), then close the terminal and open it again.

## 3. Make an admin secret

The admin secret is a password that lets your phone, and only your phone, change the relay's
settings. Paste this to make a random one:

```sh
node -e "console.log(require('crypto').randomBytes(24).toString('hex'))"
```

It prints a long line of letters and numbers. **Save it** in your password manager, or somewhere
safe you can copy it from on your phone. You'll need it twice: in step 5 and in step 6.

## 4. Put the relay on Cloudflare

Paste these one at a time:

```sh
cp wrangler.toml.example wrangler.toml
```

```sh
npx wrangler login
```

The first time, it may ask to install `wrangler` (Cloudflare's tool); type `y` and press Enter. A
browser window opens asking you to let Wrangler use your Cloudflare account: click **Allow**, then
come back to the terminal.

```sh
npx wrangler deploy
```

If it asks you to choose a `workers.dev` name, pick anything; it becomes part of your relay's
address. When it's done it prints your relay's address, something like:

```
https://pluralware-relay.your-name.workers.dev
```

**Copy that address** and keep it with the admin secret.

## 5. Give the relay its admin secret

```sh
npx wrangler secret put ADMIN_SECRET
```

When it asks for the value, paste your admin secret from step 3 and press Enter. Nothing shows as
you paste; that's normal. Until this step, the relay answers with errors, so don't worry if you
visited its address and saw one.

That's everything on the computer. You can close the terminal.

## 6. Connect your phone

In PluralWare on your phone, open **Settings → Share with friends**, and scroll to **Relay**.

1. Enter the relay's address from step 4 and the admin secret from step 3, then tap **Save relay**.
   The app shows a command starting `pk;s webhook`.
2. Tap **Copy or share the command**. In Discord, open a **direct message with PluralKit** (not a
   server channel) and send the command.
3. PluralKit replies with a signing token and waits. Copy the token, paste it into the app under
   **Token from PluralKit**, and tap **Save token**.
4. Wait for the app to say **"The relay has your settings."** Then reply `yes` to PluralKit.
   PluralKit checks the relay and tells you whether it worked.
5. Back in the app, turn on **Send through the relay**.

Now make a switch in Discord with `pk;switch`. Your friends should hear about it within a few
seconds.

PluralKit allows **one** webhook per system. If you already had something else using it, such as
a Discord bot or an automation service that announces your switches, the relay replaces it.

## Updating the relay

New versions of PluralWare sometimes need a newer relay; the app's Relay section shows an error
from the relay when that happens. To update:

1. Download the ZIP again (step 1) and open a terminal in the new `relay` folder (step 2).
2. Paste:

   ```sh
   cp wrangler.toml.example wrangler.toml
   ```

   ```sh
   npx wrangler deploy
   ```

Your address, admin secret and settings stay as they were; there's nothing to redo on the phone.

## If something goes wrong

- **PluralKit says the webhook didn't work.** Make sure you saved the token in the app, and it said
  "The relay has your settings", *before* replying `yes`. If you replied too soon, run the
  `pk;s webhook` command again; PluralKit gives a new token, which you save in the app as before.
- **The app shows an error about the admin secret, or "HTTP 401".** The secret on the phone doesn't
  match the relay's. Run step 5 again with the secret you saved, or enter that secret again on the
  phone.
- **The app shows an error when you add a friend.** The relay may be older than the app. Update it
  (above).
- **Switches stopped reaching friends after a while.** If the relay can't be reached for a long
  time, PluralKit removes the webhook. Run the `pk;s webhook` command again (step 6.2 onward).
- **You lost the admin secret.** Make a new one (step 3), give it to the relay (step 5), and enter
  it on the phone.
- **You want to see what the relay is doing.** In a terminal in the `relay` folder, run
  `npx wrangler tail`, then make a switch. It shows each request the relay gets, as it happens.
  Press Ctrl+C to stop.

## Removing the relay

In the app, tap **Remove relay**; it erases your settings from the relay. Then, in the Cloudflare
dashboard, open **Workers & Pages**, choose `pluralware-relay`, and delete it from its
**Settings**. In Discord, send PluralKit `pk;s webhook -clear` (`-clear` is how PluralKit clears a
setting) so it stops sending to the old address.
