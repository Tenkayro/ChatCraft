# ChatCraft

ChatCraft is a client-side Fabric mod for Minecraft 1.21.1. It reads messages from Twitch chat and executes accepted commands as normal Minecraft actions.

It supports movement, jumping, sneaking, sprinting, camera control, hotbar selection, inventory access, dropping items, attacking, using items, respawning, and clicking real Minecraft slots in inventories and containers.

## Requirements

- Minecraft 1.21.1
- Fabric Loader
- Fabric API
- Mod Menu
- Java 21

## Installation

1. Build the mod:

   ```powershell
   .\gradlew.bat clean build
   ```

2. The finished mod is written to:

   ```text
   build/libs/chatcraft-1.0.2.jar
   ```

3. Copy the JAR into the `mods` directory of your Minecraft or Prism Launcher instance.
4. Make sure Fabric API and Mod Menu are installed in the same instance.
5. Start Minecraft.

In VS Code, you can alternatively run the build task with `Ctrl+Shift+B`. Java 21 is already configured for this local project in `gradle.properties`.

## Registering a Twitch application

ChatCraft needs a Twitch user access token with the `chat:read` scope for a reliable authenticated connection.

1. Sign in to the [Twitch Developer Console](https://dev.twitch.tv/console/apps).
2. Enable two-factor authentication on your Twitch account if Twitch requires it.
3. Select **Register Your Application**.
4. Use these settings:

   - **Name:** `ChatCraft Local`, or another unique name
   - **OAuth Redirect URL:** `https://localhost:3000`
   - **Category:** `Chat Bot`
   - **Client Type:** `Public`

5. Click **Add** next to the redirect URL first.
6. Click **Create**.
7. Open the application and copy its **Client ID**.

You do not need a client secret. Never enter a client secret into ChatCraft.

## Generating an OAuth access token

Open PowerShell and replace the placeholder with your Client ID:

```powershell
$clientId = "YOUR_CLIENT_ID"
$state = [guid]::NewGuid().ToString("N")
$url = "https://id.twitch.tv/oauth2/authorize?response_type=token&client_id=$clientId&redirect_uri=https%3A%2F%2Flocalhost%3A3000&scope=chat%3Aread&state=$state"
Start-Process $url
```

Sign in to Twitch and authorize the application. Your browser will then try to open `https://localhost:3000`. A connection or certificate error is expected for this local setup.

The browser address bar will contain a URL similar to:

```text
https://localhost:3000/#access_token=YOUR_TOKEN&scope=chat%3Aread&state=...&token_type=bearer
```

Copy only the value between `access_token=` and the following `&`.

You can optionally validate the token:

```powershell
curl.exe -H "Authorization: OAuth YOUR_TOKEN" https://id.twitch.tv/oauth2/validate
```

The response should contain your Twitch login, the `chat:read` scope, and an `expires_in` value. Generate a new token when the current token expires or is revoked.

## Configuring ChatCraft

Open the following screen in Minecraft:

```text
Mods → ChatCraft → Configure
```

Configure these fields:

- **Twitch channel:** channel name without `#`
- **Twitch username:** username of the account that authorized the token
- **OAuth/access token:** the copied access token
- **Command prefix:** optional prefix such as `!`
- **Movement duration:** duration of movement actions in Minecraft ticks; 20 ticks are approximately one second
- **Camera step:** camera rotation per command in degrees
- **Queue size:** maximum number of pending actions
- **Cooldown:** minimum delay between accepted commands from the same user
- **Controls:** enable or disable Twitch controls
- **Slot overlay:** show or hide slot labels
- **Auto-connect:** connect automatically during startup
- **Auto-reconnect:** reconnect automatically after a connection failure

Click **Save & Done**, then use **Connect** or run `/chatcraft connect`.

When a command prefix is configured, every Twitch command must include it. For example, a prefix of `!` changes `W` to `!W`.

## Twitch commands

Commands are case-insensitive.

| Command | Action |
| --- | --- |
| `W` or `FORWARD` | Move forward |
| `S`, `BACK`, or `BACKWARD` | Move backward |
| `A` or `STRAFELEFT` | Strafe left |
| `D` or `STRAFERIGHT` | Strafe right |
| `JUMP` or `SPACE` | Jump |
| `SNEAK` or `CROUCH` | Sneak |
| `SPRINT` | Sprint |
| `E`, `INVENTORY`, or `INV` | Open the inventory |
| `Q` or `DROP` | Drop the selected item |
| `1` through `9` | Select a hotbar slot |
| `ATTACK` or `HIT` | Attack or break a block |
| `USE` or `INTERACT` | Use or interact |
| `LEFT` or `CAMLEFT` | Turn the camera left |
| `RIGHT` or `CAMRIGHT` | Turn the camera right |
| `UP` or `CAMUP` | Turn the camera up |
| `DOWN` or `CAMDOWN` | Turn the camera down |
| `RESPAWN` | Respawn after death |

## Inventory and container slots

When a handled inventory or container is open, ChatCraft renders labels such as `A1`, `B1`, and `C2` over the actual Minecraft slots.

| Example | Action |
| --- | --- |
| `A1` | Left-click slot A1 |
| `RA1` | Right-click slot A1 |
| `SA1` | Shift-click slot A1 |

ChatCraft uses Minecraft's `ScreenHandler`, `Slot`, and internal slot interaction APIs. It never uses desktop mouse coordinates.

The mapper works generically with standard handled vanilla screens, including the player inventory, crafting tables, chests, barrels, furnaces, hoppers, villagers, anvils, smithing tables, brewing stands, and enchanting tables.

## Client test commands

| Command | Function |
| --- | --- |
| `/chatcraft connect` | Connect to Twitch |
| `/chatcraft disconnect` | Disconnect and release simulated keys |
| `/chatcraft status` | Show connection status and queue size |
| `/chatcraft test <command>` | Test a command through the same parser and action queue |
| `/chatcraft clearqueue` | Clear the queue and release all simulated keys |

Examples:

```text
/chatcraft test W
/chatcraft test ATTACK
/chatcraft test A1
/chatcraft test RA1
```

If a prefix is enabled, include it in test commands as well, for example `/chatcraft test !W`.

## Whitelist and blacklist

With Minecraft closed, the whitelist and blacklist can be edited in `config/chatcraft.json`:

```json
{
  "whitelist": ["alloweduser"],
  "blacklist": ["blockeduser"]
}
```

An empty whitelist allows every user who is not blacklisted. Once the whitelist contains entries, only those Twitch users are accepted.

## Security

- Treat the OAuth access token like a password.
- Never share it in chat, screenshots, or support logs.
- Never commit `config/chatcraft.json`.
- ChatCraft does not log the token.
- Do not use a client secret with ChatCraft.
- If a token is exposed, revoke the application in your Twitch connection settings and generate a new token.

## Troubleshooting

### Twitch commands do not work

1. Run `/chatcraft status`.
2. Check the channel and Twitch username.
3. Make sure the token belongs to that exact username.
4. Validate the token with Twitch's `/validate` endpoint.
5. Make sure **Controls** is enabled.
6. Check the configured command prefix and cooldown.

### A movement key remains active

Run:

```text
/chatcraft clearqueue
```

ChatCraft also releases simulated keys automatically when Twitch disconnects, the world unloads, the client stops, or an action fails.

### Build problems

Run a complete clean build:

```powershell
.\gradlew.bat clean build
```

The project targets Minecraft 1.21.1, Java 21, Fabric, and Yarn mappings.
