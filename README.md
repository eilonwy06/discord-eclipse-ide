# Discord Rich Presence for Eclipse IDE

Show what you are working on in Eclipse on your Discord profile, and keep Discord itself open in
an Eclipse view.

> **Credit where it's due.** This project is a fork of
> [discord-vscode](https://github.com/iCrawl/discord-vscode) by Noel Buechler (iCrawl) and
> contributors, the Discord Rich Presence extension for Visual Studio Code. The idea, the
> activity templates and their `{tokens}`, the setting names and the language-icon table all
> come from there. From the fork point on, the VS Code extension was replaced by an
> Eclipse-specific implementation; the original lives on in this repository's history and,
> of course, upstream.

*Not affiliated with or endorsed by Discord, the Eclipse Foundation, or the discord-vscode
authors.*

## What it does

- **Rich Presence.** Your profile shows "Playing Eclipse IDE" with what you are doing:
  `Editing PresenceService.java`, `Project: my-app`, a language icon, the elapsed time,
  "Debugging …" while a debug launch runs, and an optional *View Repository* button.
- **A Discord view.** Discord's own web client in a workbench view, so you can read and answer
  messages without leaving the IDE.
- A status bar item showing the connection; click it for Reconnect, Enable/Disable, Open Discord
  and Preferences.

## Repository layout

```
com.eilonwy.discord.eclipse/   the plug-in (PDE project)
LICENSE                        MIT, upstream copyright retained
```

## Running it

1. Import `com.eilonwy.discord.eclipse` into Eclipse as an existing project (it carries its
   `.project` / `.classpath` / PDE nature).
2. **Run As → Eclipse Application**.
3. The status bar shows **Discord: needs setup** until it has an Application ID (below).
4. **Window → Show View → Other… → Discord → Discord** opens the web client.

Requirements: Java 21+, an Eclipse with PDE, and the Discord desktop app running on the same
machine. No Maven/Tycho build is included yet.

## One-time setup: a Discord application

Rich Presence always shows as some Discord *application*, and its name is the "Playing …" text.

1. Open https://discord.com/developers/applications → **New Application**, and name it after what
   should follow "Playing" (for example `Eclipse IDE`).
2. Copy the **Application ID** from *General Information*.
3. In Eclipse: **Window → Preferences → Discord Rich Presence → Application ID**, paste, Apply.
4. Optional: under *Rich Presence → Art Assets*, upload an image with the key **`eclipse`**. It
   becomes the small image. The Eclipse logo ships with every Eclipse install as
   `plugins/org.eclipse.platform_*/eclipse1024.png`.

Discord also needs *User Settings → Activity Privacy → Share your detected activities* switched
on.

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
  Iframes are left alone, because the sign-in captcha is one.
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
