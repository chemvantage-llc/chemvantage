# Chemistry Real-World Reasoning Standalone

This folder is an independent copy of the chemistry reasoning experience. It does not modify or depend on the main app.

## Contents

- `index.html`: static page shell
- `styles.css`: standalone styling
- `app.js`: vanilla JavaScript quiz logic
- `data/chemistry-reasoning-questions.json`: the chemistry reasoning questions
- `assets/dictionary/`: copied character and speech-bubble assets
- `assets/feedback/`: copied check/cross and answer feedback assets

## Run Locally

Because the page loads JSON with `fetch`, serve this folder with any static server:

```bash
npx serve chemistry-reasoning-standalone
```

Or from inside this folder:

```bash
npx serve .
```

Then open the localhost URL printed by the server.

## Integration Notes

The quiz expects the correct answer to be option index `0` in the JSON. The UI shuffles options at render time while preserving that answer key internally.

The fixed prompt is:

```text
Based on this conversation, what conclusions can we reasonably draw?
```
