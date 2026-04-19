#!/bin/bash
# Download required fonts for the openCrow Android app.
# Run from the openCrow-app directory.

FONT_DIR="app/src/main/res/font"
mkdir -p "$FONT_DIR"

echo "Downloading Space Grotesk..."
curl -sL "https://github.com/floriankarsten/space-grotesk/raw/master/fonts/ttf/SpaceGrotesk-Regular.ttf" -o "$FONT_DIR/space_grotesk_regular.ttf"
curl -sL "https://github.com/floriankarsten/space-grotesk/raw/master/fonts/ttf/SpaceGrotesk-Medium.ttf" -o "$FONT_DIR/space_grotesk_medium.ttf"
curl -sL "https://github.com/floriankarsten/space-grotesk/raw/master/fonts/ttf/SpaceGrotesk-SemiBold.ttf" -o "$FONT_DIR/space_grotesk_semibold.ttf"
curl -sL "https://github.com/floriankarsten/space-grotesk/raw/master/fonts/ttf/SpaceGrotesk-Bold.ttf" -o "$FONT_DIR/space_grotesk_bold.ttf"

echo "Downloading Inter..."
curl -sL "https://github.com/rsms/inter/raw/master/docs/font-files/Inter-Regular.ttf" -o "$FONT_DIR/inter_regular.ttf"
curl -sL "https://github.com/rsms/inter/raw/master/docs/font-files/Inter-Medium.ttf" -o "$FONT_DIR/inter_medium.ttf"
curl -sL "https://github.com/rsms/inter/raw/master/docs/font-files/Inter-SemiBold.ttf" -o "$FONT_DIR/inter_semibold.ttf"

echo "Downloading JetBrains Mono..."
curl -sL "https://github.com/JetBrains/JetBrainsMono/raw/master/fonts/ttf/JetBrainsMono-Regular.ttf" -o "$FONT_DIR/jetbrains_mono_regular.ttf"
curl -sL "https://github.com/JetBrains/JetBrainsMono/raw/master/fonts/ttf/JetBrainsMono-Medium.ttf" -o "$FONT_DIR/jetbrains_mono_medium.ttf"

echo "Done. Fonts saved to $FONT_DIR"
echo "After downloading, update Type.kt to use Font(R.font.*) references."
