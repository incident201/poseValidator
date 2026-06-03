PoseGuard Android launcher icon resources, adaptive v2.

Unpack this archive into the repository root with replacement.

Important:
- Keep app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
- Keep app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml

These XML files are adaptive icon descriptors, not vector images. They are needed on modern Android / Pixel launchers so the icon is not treated as a legacy bitmap and placed inside an extra white circle.

The actual artwork layers are WebP bitmap resources:
- mipmap-*/ic_launcher.webp
- mipmap-*/ic_launcher_round.webp
- mipmap-*/ic_launcher_foreground.webp
- mipmap-*/ic_launcher_monochrome.webp
