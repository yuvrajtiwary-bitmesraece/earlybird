# Logo cards

Each alert shows the company's logo card above the text (issue #10). Cards are 1200×600 PNGs in
`src/main/resources/static/logos/<domain>.png`, served at `/logos/<domain>.png`.

To regenerate them, for example after adding companies to `seed-boards.csv`:

```bash
python -I scripts/logos/find_logos.py src/main/resources/boards/seed-boards.csv /tmp/logos/wikidata.json
python -I scripts/logos/fetch_images.py /tmp/logos/wikidata.json /tmp/logos/raw
java -Djava.awt.headless=true scripts/logos/LogoCards.java src/main/resources/boards/seed-boards.csv /tmp/logos/raw src/main/resources/static/logos
```

1. `find_logos.py` looks each company up on Wikidata. It accepts an item only if its official website matches the
   board's domain, so "Toast" or "Block" can't pick up the wrong company. It then records the item's logo.
2. `fetch_images.py` downloads the logo from Wikimedia Commons as a 1200 px PNG. If there's no Wikidata logo, it uses
   the company's own largest PNG/JPEG app icon instead (apple-touch-icon, web manifest, `<link rel=icon sizes>`).
3. `LogoCards.java` renders each card:
   - A wide wordmark that already spells the name is shown on its own.
   - A square icon gets the company name underneath.
   - Anything under 150 px, or listed in `NAME_ONLY`, gets a name-only card, because an upscaled icon looks blurry.
   It runs on Windows because it uses the Segoe UI Semibold font for names.

Look at the result before committing. A contact sheet of the PNGs is the quickest check. Then bump
`AlertFormatter.LOGO_VERSION`, because Telegram caches each preview URL. `LogoCardsTest` fails if a seed company
has no card.

Logos are trademarks of their owners. They're shown only to identify the company whose job is being announced.
