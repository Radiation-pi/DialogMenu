# DialogMenu

Standalone YAML-driven menus rendered with Minecraft's Dialog API. The plugin
contains only the `layout-demo` and `layout-detail` examples; it does not ship
server-specific menus, images, or configuration.

## Requirements

- Java 25
- Paper-compatible server exposing the Minecraft 26.3 Dialog API
- A client that supports Dialog and accepts the server resource pack
- Source is under `src/main/java/dev/dialogmenu/`; only the two public test
  layouts are included under `src/main/resources/layouts/`.

Skript, PlaceholderAPI, and CraftEngine are optional integrations. Use the
corresponding plugin only when your configuration relies on that integration.

## Install

1. Put `DialogMenu-0.0.6.jar` in the server's `plugins/` directory and start the
   server.
2. Accept the server resource pack.
3. Run `/dialogmenu list`, then `/dialogmenu open layout-demo`.

The bundled demo opens `layout-detail`; its buttons demonstrate back, message,
and close actions. Copy the two YAML files from
`plugins/DialogMenu/layouts/` to make your own layouts, then run
`/dialogmenu reload`.

With CraftEngine installed, DialogMenu registers its generated resource pack
with CraftEngine. Without CraftEngine, run `/dialogmenu resources` and publish
the generated `plugins/DialogMenu/resourcepack/` through your server's normal
resource-pack delivery.

## Source

The Java files are the neutralized v0.0.6 source snapshot. The source tree was
compiled against the release classpath: 51 of 54 generated classes matched the
published JAR byte-for-byte; the other three compiled successfully but were
not byte-identical. No unfinished editor code or server-specific files are
included.

## Build and test status

Release 0.0.6 was compiled for Java 25 and passed offline layout, resource,
and click-token checks. These checks do not replace testing on your server and
client; client-side display and interaction have not been verified here.
