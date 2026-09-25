# Skins and capes

This page covers the **Skin and cape** window: choosing a skin, the 3D preview, templates, and managing skins and capes via Mojang's official Profile API. For accounts, sign-in and credential storage, see [configuration.md](configuration.md).

## Opening the window

Select an account in the account row at the bottom of the main window and click **Skin and cape...**. The button is disabled when no account is selected. The window edits the selected account only.

The left half is an interactive 3D preview of the player model. The right half contains controls for **Skin**, **Cape**, and official synchronization with Mojang services. **Save** writes the local cache to `skins/skins.json`. **Cancel** discards unapplied changes.

## Skin and cape customization

| Property | Implementation |
|---|---|
| Where the skin lives | In the official Mojang profile |
| Skin | **Choose...**, **Clear**, **Template...**, model selection, **Upload to Mojang** |
| Cape | Official capes granted by Mojang to the account: list and **Wear** |
| Visibility | Visible to all players on all servers |
| Changes are sent | Immediately upon clicking **Upload to Mojang** or **Wear** |

## Choosing a skin file

Click **Choose...** and select a PNG file, or drop one PNG file onto the preview area. The launcher reads the PNG header and verifies compliance, refusing files that are not PNG, exceed 2 MB, or have invalid dimensions:

| Kind | Accepted sizes |
|---|---|
| Skin | 64x64, or 64x32 (legacy format). Also valid multiples, such as 128x128 |
| Cape | 64x32 or 22x17 (classic cape format) |

The selected file is cached locally in `skins/` as `skin-<hash>.png` (the first 16 characters of its SHA-1 hash). You can safely move or delete the original file. **Clear** resets the local preview.

**Model type.** The model selector sets the arm width: **Classic (4 px arms)** or **Slim (3 px arms)**. When opening an account's customization window, the list defaults to the model currently registered with the player's Mojang account.

## The 3D preview

The preview window renders an accurate 3D model of the character:

- The figure rotates slowly by default and pauses when hovered.
- Drag to rotate the character freely in any direction (vertical angle is clamped to 80 degrees).
- Mouse wheel zooms the camera in and out.
- Navigation buttons at the bottom provide step-by-step camera adjustments without mouse dragging.
- Renders full 64x64 layered textures, including outer hat, jacket, sleeves, and pants layers.

## Templates

**Template...** (next to **Choose...**) prompts for an output folder and generates reference files:

| File | Content |
|---|---|
| `skin-template.png` | A blank 64x64 canvas with base areas filled and overlay areas marked transparent, matching the active model (Classic or Slim) |
| `skin-guide.png` | An expanded guide with each body part, side, and pixel coordinate labelled for easy editing |

Existing files are preserved without overwriting (the launcher appends numeric suffixes `-2`, `-3`).

## Mojang profile integration: upload and capes

When the window opens, the launcher retrieves the account's active skin and capes directly from `https://api.minecraftservices.com/minecraft/profile`.

- **Upload to Mojang** uploads the chosen skin file with the selected model directly to Mojang's services via standard multipart requests. The file must adhere to 64x64 or 64x32 dimensions.
- The cape selector lists **No cape** alongside every cape officially granted to the Microsoft account. Clicking **Wear** activates the chosen cape or removes it.

These actions use the authenticated player's Minecraft access token.

## Files and cache

| Path (in data folder) | Content |
|---|---|
| `skins/skin-<hash>.png` | Cached copies of selected skin textures |
| `skins/skins.json` | Local mapping of accounts and their cached preview settings |

Session credentials used to communicate with Mojang's services are held in the operating system's native credential vault (see [SECURITY.md](../SECURITY.md)) and are never written to plain-text files.