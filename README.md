# DialogMenu

Standalone YAML-driven menus rendered with Minecraft's Dialog API. The plugin
contains only the `layout-demo` and `layout-detail` examples; it does not ship
server-specific menus, images, or configuration.

## Requirements

- Java 25
- Paper-compatible server exposing the Minecraft 26.3 Dialog API
- A client that supports Dialog and accepts the server resource pack
- Build: `./gradlew build`; output: `build/libs/DialogMenu-<version>.jar`
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

The complete v0.0.6 Java source, resources, Gradle build, and wrapper are in
this repository. The build resolves Paper and Skript compile-time APIs from
their public Maven repositories. CraftEngine integration is optional and
loaded reflectively, so CraftEngine is not required to build. Only the two
public test layouts are included. No unfinished editor code or server-specific
files are included.

## Publishing a new version

1. Update `version` in `gradle.properties`; `plugin.yml` receives it during
   the Gradle resource-processing step.
2. Build and verify with `./gradlew clean build`.
3. Commit the updated source and push it to `main`.
4. Push a matching version tag, for example `git tag v0.0.7 && git push origin v0.0.7`.

The tag workflow checks the version, builds the JAR from the committed source,
packages the public source, and creates a GitHub Release with both files. A
failed build or privacy/layout check prevents the release.

## Build and test status

Release 0.0.6 was compiled for Java 25 and passed offline layout, resource,
and click-token checks. These checks do not replace testing on your server and
client; client-side display and interaction have not been verified here.
