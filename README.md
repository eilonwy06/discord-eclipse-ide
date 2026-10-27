# Discord Presence & Chat for Eclipse IDE

Show what you are working on in Eclipse on your Discord profile, and keep Discord itself open in
an Eclipse view.

> This project is a fork of
> [discord-vscode](https://github.com/iCrawl/discord-vscode) by Noel Buechler (iCrawl) and
> contributors, the Discord Rich Presence extension for Visual Studio Code. The idea, the
> activity templates and their `{tokens}`, the setting names and the language-icon table all
> come from there. From the fork point on, the VS Code extension was replaced by an
> Eclipse-specific implementation; the original lives on in this repository's history and,
> of course, upstream.

*Not affiliated with or endorsed by Discord, the Eclipse Foundation, or the discord-vscode
authors. Discord and the Discord logo are trademarks of Discord Inc.; Eclipse and the Eclipse logo
are trademarks of the Eclipse Foundation. They are used here only to identify the products this
plug-in works with.*

## What it does

- **Rich Presence.** Your profile shows "Playing Eclipse IDE" with what you are doing:
  `Editing PresenceService.java`, `Project: my-app`, a language icon, the elapsed time,
  "Debugging …" while a debug launch runs, and an optional *View Repository* button.
- **A Discord view.** Discord's own web client in a workbench view, so you can read and answer
  messages without leaving the IDE.
- **A Discord perspective.** *Window → Perspective → Open Perspective → Discord* puts Discord
  beside the editor, with the Project Explorer on the left and Problems and Console below the
  editor. Every other perspective lists it in its Open Perspective shortcuts, and lists the
  Discord view in its Show View shortcuts.
- A status bar item showing the connection; click it for Reconnect, Enable/Disable, Open Discord
  and Preferences.

## Install

Requirements: Eclipse 2024-12 or newer (or any Eclipse running on Java 21+), and the Discord
desktop app running on the same machine.

- **Update site:** in Eclipse, **Help → Install New Software… → Add…** and enter
  `https://eilonwy06.github.io/discord-eclipse-ide/`. Then tick *Discord Presence & Chat for
  Eclipse IDE* and finish the wizard.
- **Eclipse Marketplace:** listing coming soon.

After a restart, the status bar shows **Discord: connected** and your profile shows "Playing
Eclipse IDE". No setup is needed. Open the chat view with **Window → Show View → Other… → Discord →
Discord**.

Discord needs *User Settings → Activity Privacy → Share your detected activities* switched on.

## Using your own Discord application (optional)

By default the plug-in shows as this project's Discord application, "Eclipse IDE". To show a
different name after "Playing", or your own art:

1. Open https://discord.com/developers/applications → **New Application**, and name it after what
   should follow "Playing".
2. Copy the **Application ID** from *General Information*. It's a public identifier, not a secret.
3. Under *Rich Presence → Art Assets*, upload an image with the key **`eclipse`**. It becomes the
   small image. The Eclipse logo ships with every Eclipse install as
   `plugins/org.eclipse.platform_*/eclipse1024.png`.
4. In Eclipse: **Window → Preferences → Discord Rich Presence → Application ID**, paste, Apply.

## Repository layout

```
com.eilonwy.discord.eclipse/          the plug-in
com.eilonwy.discord.eclipse.feature/  the installable feature
com.eilonwy.discord.eclipse.site/     the p2 update site
discord-eclipse.target                target platform: Eclipse 2024-12, the oldest supported
pom.xml                               Tycho build
.github/workflows/                    CI build, and the manual update-site deploy to GitHub Pages
LICENSE                               MIT, upstream copyright retained
```

## Building from source

- **Command line:** `mvn verify` with Java 21+ and Maven 3.9. The update site lands in
  `com.eilonwy.discord.eclipse.site/target/repository`.
- **In Eclipse:** import the three projects, then run `com.eilonwy.discord.eclipse` with **Run As →
  Eclipse Application**.

To publish, run the **Deploy update site** workflow from the GitHub Actions tab.

## Rich Presence compared with discord-vscode

| | discord-vscode | this plug-in |
|---|---|---|
| Top line | `Editing {file_name}` / `Debugging {file_name}` / `Idling` | same |
| Bottom line | `Workspace: {workspace}` | `Project: {project}`, because an Eclipse workspace holds many projects |
| Large image | language icon, `Editing a {LANG} file` | same, same `languages.json` |
| Small image | the VS Code logo; a bug while debugging | your `eclipse` art asset; the debug icon while debugging |
| Elapsed time | kept across editor switches, reset after idling | same |
| *View Repository* button | from VS Code's Git extension | read straight from `.git`, credentials stripped |
| Idle | clears after `idleTimeout` seconds unfocused | same |
| Exclusions | `workspaceExcludePatterns` | one regex over workspace and project paths |
| Discord connection | `@xhayper/discord-rpc` | own Java implementation of Discord's IPC protocol |

**Tokens** for every text field: `{file_name}` `{dir_name}` `{full_dir_name}` `{project}`
`{workspace}` `{workspace_folder}` `{workspace_and_folder}` `{lang}` `{Lang}` `{LANG}`
`{current_line}` `{current_column}` `{total_lines}` `{current_errors}` `{file_size}` `{git_branch}`
`{git_repo_name}` `{app_name}` `{empty}`. In Eclipse terms, `{workspace}` is the workspace name
(*General → Workspace*, else the folder name), and `{workspace_folder}` / `{project}` are the
file's project. Every occurrence of a token is replaced.

**Images.** Language, idle and debug icons are sent as URLs,
`<Language icon base URL><name>.png`. The default base points at discord-vscode's icon set as it
was at the fork point (commit `6d5fb18`), served from this repository. Clear the base URL to send
bare names instead, as art-asset keys of your own application.

**Privacy.** Everything shown is visible to anyone who can see your profile. The *View
Repository* button links to the Git remote. For private repositories, hide it in the preferences
or add an exclusion regex.

**Two editors at once.** Discord shows one Rich Presence at a time, so with VS Code and Eclipse
both open, whichever connected first is shown. Set an idle timeout in both
(`discord.idleTimeout` in VS Code) so the editor you are not using steps aside.

## How it is built

- `rpc/DiscordIpc`: the transport. A Windows named pipe `\\.\pipe\discord-ipc-N` via
  `RandomAccessFile`, or a Unix domain socket elsewhere, including Flatpak and Snap paths. Frames
  are `opcode, length, json`, little-endian.
- `rpc/PresenceClient`: one worker thread does all pipe I/O, writing a command and then reading
  its reply. On Windows the pipe is a synchronous handle, where a separate reader thread would
  block every write. Updates coalesce (latest wins) and go out at most every 4 s, inside
  Discord's limit of about five per 20 s. It reconnects every 15 s while Discord is away. An
  Application ID that Discord refuses is not retried until the ID changes or you click Reconnect.
- `presence/*`: `Snapshot` (the editor state), `ActivityBuilder` (activity rules following
  discord-vscode's `activity.ts`), `Languages` (`languages.json` matching, including its
  JavaScript regex keys), and `GitInfo` (branch and remote from `.git`, worktrees included).
- `PresenceService`: listens to windows, parts, selection, documents, launches and markers,
  debounced to one snapshot per 750 ms. The snapshot is taken on the UI thread; file size and Git
  are read on a builder thread.

## The Discord view

- Loads `https://discord.com/app` in Edge (WebView2). The sign-in lives in SWT's shared WebView2
  profile, so it should persist across restarts.
- Keyboard input: on view focus and after page load, Win32 focus is handed to WebView2's render
  window and a harmless Shift press is posted, through SWT's internal bindings in pure Java. This
  works around WebView2 intermittently refusing keys inside workbench views.
- Discord stays in the view. Other top-level navigation and popups open in the system browser.
  Iframes are left alone, because the sign-in captcha is one. The view has no toolbar or view menu:
  it is just Discord.
- Offline: before loading, the view checks that Discord can be reached. If it can't, it shows a
  self-contained "No internet connection" page with a *Try again* button, and retries on its own
  every 10 s until Discord loads. Once Discord is open, its own client handles dropped connections.
- Nothing is injected into Discord's page and the account is never automated. Discord's terms
  forbid automating a user account.
- There is no Internet Explorer fallback; without WebView2 the view says so instead.

## Status

Verified:

- The IPC transport and the handshake against the real Discord desktop app, including a refused
  Application ID (code 4000) and a live connection with a real one.
- Activity building for every token and option, `languages.json` matching (all 414 keys), Git
  remote handling, and Discord's field limits. These were unit-run on plain Java against Gson
  2.8.9 and 2.14.0.
- The PDE build (0 errors), and running as an Eclipse Application with the status bar showing
  the connection.

Not yet verified:

- Typing in the Discord view, and the sign-in persisting across restarts.
- The *View Repository* button, which only other people can see.

Known gaps:

- Discord quitting is noticed on the next update, so the status bar can say *connected* until
  then.
- Voice and video in the view are untested.
- Files that match no `languages.json` rule show the `text` icon. Eclipse has no equivalent of VS
  Code's language ids to fall back on.

## License

MIT. See [LICENSE](LICENSE). The upstream copyright notice is retained as the license requires.
Third-party details are in
[`com.eilonwy.discord.eclipse/THIRD-PARTY-NOTICES.md`](com.eilonwy.discord.eclipse/THIRD-PARTY-NOTICES.md).
