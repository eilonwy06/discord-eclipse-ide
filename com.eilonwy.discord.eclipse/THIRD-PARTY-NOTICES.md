# Third-party notices

## discord-vscode

This repository is a fork of discord-vscode (https://github.com/iCrawl/discord-vscode), forked
at upstream commit `6d5fb18969e1dba1e64f86d7fb758896aed1f1b3`.

- `resources/languages.json` is copied unchanged from that commit (`src/data/languages.json`).
- By default the plug-in loads that project's icons from the same commit at runtime; they are not
  bundled.
- The Rich Presence behaviour, templates and setting names follow its `src/activity.ts` and
  `src/extension.ts`, reimplemented in Java for Eclipse.

```
MIT License

Copyright (c) 2017 Noel Buechler
Copyright (c) 2017 Khinenw

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
