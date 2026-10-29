# LiveTag

Paper 26.3 plugin. Streamers toggle a [LIVE] tag shown next to their name in the
tab list, above their head, and in chat.

## Build
Requires JDK 25 and Maven 3.9+:

    mvn clean package

Jar: `target/LiveTag-1.0.0.jar` -> put it in `plugins/`.

## Use
- `/live`        toggle on/off  (aliases: /stream, /streamer)
- `/live menu`   GUI with a clickable button
- `/live on|off`

## Permissions
- `livetag.use`   who can toggle (default: nobody - give it to streamers)
- `livetag.admin` `/live reload` (default: op)

Edit the tag text/colour in `config.yml` (MiniMessage format).
