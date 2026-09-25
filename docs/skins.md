# Skins and capes

This page covers the **Skin and cape** window: choosing a skin, the 3D preview, templates, uploading to Mojang, and how offline accounts get skins in the game. For accounts, sign-in and the credential store, see [configuration.md](configuration.md).

## Opening the window

Select an account in the account row at the bottom of the main window and click **Skin and cape...**. The button is disabled when no account is selected. The window edits the selected account only.

The left half is a 3D preview of the player. The right half has three sections: **Skin**, **Cape** and, for offline accounts only, **Skins are served by**. **Save** writes the choices to `skins/skins.json`. **Cancel** discards them. Some actions do not wait for **Save** (see below).

## Offline and Microsoft accounts

| | Offline account | Microsoft account |
|---|---|---|
| Where the skin lives | On this computer, or at a skin service you sign in to | At Mojang |
| Skin | **Choose...**, **Clear**, **Template...**, model | **Choose...**, **Clear**, **Template...**, model, **Upload to Mojang** |
| Cape | Any cape PNG: **Choose...**, **Clear**, **Template...** | Only capes that Mojang granted to the account: list and **Wear** |
| Who sees it | See [What other players see](#what-other-players-see) | Every player on every server |
| Changes are sent | At launch | Immediately, when you click **Upload to Mojang** or **Wear** |

## Choosing a skin or cape

Click **Choose...** and select a PNG file, or drop one PNG file on the preview. The launcher reads the PNG header and refuses files that are not PNG, are larger than 2 MB, or have a size it does not accept:

| Kind | Accepted sizes |
|---|---|
| Skin | 64x64, or 64x32 (before 1.8). Also any multiple of these, for example 128x128 or 512x512 |
| Cape | 64x32 or a multiple of it (for example 128x64), or 22x17 (the old cape format) |

The file is copied into `skins/` as `skin-<hash>.png` or `cape-<hash>.png` (the first 16 characters of its SHA-1). You can move or delete the original after that. **Clear** removes the picture from the account. The copy stays in `skins/` until you remove it in the storage window (see [builds-and-storage.md](builds-and-storage.md)).

When you drop a file, the launcher decides from its size where it goes. A 22-pixel-wide file is a cape. A 64x32 file is a cape if the account already has a skin and no cape. All other files are skins.

**Resizing for the game.** Minecraft only accepts a skin sheet of 64x64 or 64x32 and discards other sizes. For a larger sheet the launcher serves the game a copy at 64x64 (or 64x32 for a half-height sheet), made by averaging each block of pixels. A 22x17 cape is served on a 64x32 sheet. The file in `skins/` does not change, and the preview shows it at full size. The window shows a note when a file will be resized. This applies to the local skin service only. **Upload to Mojang** does not resize: it accepts only 64x64 or 64x32 files.

**Model.** The list below the skin buttons sets the arm width: **Classic (4 px arms)** or **Slim (3 px arms)**. The same sheet looks wrong with the wrong model. For a Microsoft account the list starts at the model of the skin Mojang has now.

## The 3D preview

- The figure turns slowly by itself. It stops while the pointer is over it.
- Drag to turn it in any direction. The up and down angle stops at 80 degrees.
- The mouse wheel moves the camera closer or further.
- The four buttons at the bottom do the same without a mouse: turn left, turn right (20 degrees each), further, closer.

The preview shows what the game will use:

- Local source: the files chosen in this window.
- Microsoft account: the chosen file, or the skin Mojang has now if no file is chosen.
- Signed in to a skin service: the skin and cape that the service has for that account. If the account there has no skin, the preview shows a stand-in figure drawn by the launcher. It is not Minecraft's default skin, which the game uses in that case.

## Templates

**Template...** (next to **Choose...**) asks for a folder and writes two PNG files into it:

| File | Content |
|---|---|
| `skin-template.png` | A blank 64x64 sheet. Base areas are filled with grey, overlay areas are transparent. Uses the model selected now (Classic or Slim) |
| `skin-guide.png` | The same layout at 12 times the size, with each rectangle outlined and labelled with the part, the side and its size in pixels |
| `cape-template.png`, `cape-guide.png` | The same for a 64x32 cape (offline accounts only) |

Existing files are not overwritten. The launcher adds `-2`, `-3` and so on to the name. Open the template in an image editor, draw on it, and choose the result with **Choose...**.

## Microsoft accounts: upload and capes

When the window opens, the launcher reads the account's current skin and capes from `https://api.minecraftservices.com/minecraft/profile`.

- **Upload to Mojang** sends the chosen skin file with the selected model. The file must be 64x64 or 64x32 pixels.
- The cape list shows **No cape** and every cape the account owns. **Wear** makes the selected cape active, or removes the cape when **No cape** is selected. Mojang has no way to upload your own cape.

These requests use the account's Minecraft access token and happen only when you click the button. **Cancel** does not undo them.

The chosen file is also saved in `skins.json` for a Microsoft account. The launch code does not check the account type, so at launch it attaches the local skin service (below) to that account too. If you only want the skin at Mojang, click **Clear** before **Save** after the upload.

## Offline accounts: where the game gets the skin

An offline account has no profile at Mojang, so the game shows a default skin. The launcher can point the game at another skin service with [authlib-injector](https://github.com/yushijinhun/authlib-injector), a Java agent that replaces the addresses in the game's authentication library. Under **Skins are served by**, choose one of two sources.

### This launcher, on this machine

At launch the launcher starts a small skin service for this game session only:

- It listens on `127.0.0.1` on a free port. Nothing outside this computer can reach it.
- It answers only for the account being launched and serves only the chosen skin and cape. For all other players it answers "no textures", so the game shows them with the default skin.
- It signs the texture data with an RSA key kept in `skins/signing-key.json`. The key is made on first use and is not a credential.
- It stops when the game exits.

No account and no internet connection are necessary. You see your skin in single-player and on every server. Other players do not, because their game asks their own skin service about you.

### A skin service on the network

Use a service that implements the Yggdrasil API, for example LittleSkin, Ely.by or a self-hosted Blessing Skin. You need an account there, and you upload the skin on the service's website.

1. Type the service's API address, for example `https://littleskin.cn/api/yggdrasil`. The address must start with `https://`.
2. Click **Sign in...** and type the e-mail or name and the password of your account at that service.
3. Click **Save**.

The sign-in is saved as soon as it succeeds, not at **Save**. The line under the buttons shows who you are signed in as, and warns when the saved sign-in belongs to a different address than the one in the field. Sign-ins are kept per service, so you can change the address and change back without signing in again. **Sign out** deletes the saved sign-in from this computer. To revoke the token, use the service's website.

At launch the game starts as the account at the service: its name, its UUID and its token. The launcher checks the token first and renews it if necessary. If you are not signed in, or renewal fails, the game starts as the offline account and the log says why. That account is unknown to the service, so no skin appears.

A file chosen in this window while the source is a network service is saved, but the game does not show it.

### What other players see

| Setup | You | Other players |
|---|---|---|
| Microsoft account | Your Mojang skin | Your Mojang skin, on every server |
| Offline, this launcher | Your skin, in single-player and on every server | The default skin |
| Offline, skin service | Your skin from the service | Your skin and cape, on a server that is set up for the same service |

A skin problem never stops the game. If the agent cannot be downloaded, the local port cannot be opened or the token cannot be renewed, the launcher writes the reason to the log and starts the game without skins.

## authlib-injector

The agent is added to a launch only when the account needs it: a local source with a skin or cape, or a network service with an address. It goes on the Java command line before the version's own JVM arguments, so it loads before the authentication library:

```
-javaagent:<data folder>/agents/authlib-injector.jar=<service address>
-Dauthlibinjector.yggdrasil.prefetched=<service metadata, base64>   (local service only)
-Dauthlibinjector.noShowServerName
```

The jar is `agents/authlib-injector.jar` in the data folder. If a non-empty file is there, the launcher uses it and downloads nothing, so you can put a copy you reviewed there. If not, on the first launch that needs it the launcher:

1. Reads `https://authlib-injector.yushi.moe/artifact/latest.json`.
2. Refuses a download address that does not start with `https://`.
3. Downloads the jar and compares it with the SHA-256 in that file, if the file gives one. On a mismatch it refuses the jar.
4. Writes `authlib-injector.jar.part` and then renames it, so an interrupted download is never used.

The hash and the jar come from the same publisher. The check finds damaged downloads, not a compromised publisher. See [../SECURITY.md](../SECURITY.md).

## Files and credentials

| Path (in the data folder) | Content |
|---|---|
| `skins/skin-<hash>.png`, `skins/cape-<hash>.png` | Copies of the chosen pictures |
| `skins/skins.json` | For each account (`offline:<uuid>` or `microsoft:<uuid>`): `source` (`local` or `remote`), `model` (`classic` or `slim`), `skin`, `cape`, `service` |
| `skins/signing-key.json` | Signing key of the local skin service |
| `agents/authlib-injector.jar` | The skin agent |

Skin service sign-ins go to the same credential store as the Microsoft tokens, under the key `skin-service:<account id>:<service address>`. Each entry holds the service address, the access token, the client token, and the UUID and name of the profile at the service. The password is not stored. The client token stays the same when you sign in again, so a renewal does not end the same account's sessions in other programs. The access and client tokens are registered with the log redactor and do not appear in logs. If no credential store is available, **Sign in...** refuses and the window says why.
