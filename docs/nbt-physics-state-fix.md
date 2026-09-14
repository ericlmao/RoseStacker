# NBT stack physics state

NBT storage previously allowed a normal member to inherit `NoAI`, `NoGravity`,
or persistent-data flags from the stack template. Minecraft omits the two native
flags when false, and recursively merging a member's complete `BukkitValues`
compound resurrected template keys absent from that member.

All NBT adapters now record explicit false values for absent native flags and an
explicit empty `BukkitValues` compound before deduplication. Reconstruction copies
each member's top-level override as a whole, matching the top-level deduplication
format. Deliberately disabled mobs, clone state, and other plugins' member data
are preserved. No storage format version change is required.

Run the regression suite and packaged build with:

```sh
./gradlew :NMS:v26_2_R1:test build
```

The tests exercise real NBT storage, stack transfers, cloning, serialized reloads,
explicit empty legacy PDC, intentional flags, and independence of returned tags.
They do not start a server or verify mob movement in a live farm. The standard
build compiles the enabled 1.21 and 26.x adapters; the older adapters remain
disabled in `settings.gradle`.

## Existing affected stacks

Installing this fix prevents new members from inheriting the wrong state. It
does not automatically clear flags on live entities or ambiguous old entries.
An omitted field in an old entry can mean either intentional deduplication or a
lost false value; those cases cannot be distinguished from the saved data alone.
Existing explicit empty PDC overrides are reconstructed correctly by this fix.

Before repairing an existing stack, inspect its native `NoAI` and `NoGravity`,
`Bukkit.Aware`, `BukkitValues."rosestacker:no_ai"`, storage type, and applicable
entity/spawner settings. Check the actual bounding box if the mob is over a hole.
Changing only the visible head may leave the template and stored members flagged.
Do not bulk-clear flags: some mobs may intentionally have disabled AI or gravity.

The storage bug was reproduced independently of the reported video. The video's
specific cause and the source of any initial native flags remain unconfirmed.
