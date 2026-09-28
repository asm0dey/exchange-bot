# Mini app mockups

Visual reference for the mini app, agreed on 2026-09-27. The built app should
follow the same idea, with controls close to these. It doesn't have to be
pixel-identical. If something here doesn't work in practice, raise it; don't
silently redesign it.

- `telegram-light.png`, `telegram-dark.png`: all six screens in Telegram's two
  default themes.
- `mockups.html`: the source. Open it in a browser; the toggle switches themes.
  To re-shoot:

      chromium --headless=new --hide-scrollbars --window-size=1600,1900 \
        --screenshot=telegram-light.png "file://$PWD/mockups.html"

Names, amounts and the 94.12 reference rate are sample data. The rules the
screens encode (Gives/Wants wording, ≈ amounts, accepted ranges, one "New
request" label) are specified in `../2026-09-27-miniapp-design.md`.
