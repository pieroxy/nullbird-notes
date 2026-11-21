#!/bin/sh
# Makes the webapp's icons from icon.png (1150x1150, edited in icon.xcf). Needs ImageMagick.
# Run from this folder: ./make-icons.sh
set -e
out=../src/notes-webapp/src/icons
bg="#f5f6f8"
for size in 32 192 512; do
  convert icon.png -resize ${size}x${size} -strip "$out/icon-$size.png"
done
# iOS rounds the corners itself, and wants no transparency: the icon has none.
convert icon.png -resize 180x180 -strip "$out/apple-touch-icon.png"
# Maskable: Android may crop to a circle of 80% of the width; the N reaches the corners of that
# circle at full size, so it's shrunk to 80% on the same background.
convert icon.png -resize 920x920 -background "$bg" -gravity center -extent 1150x1150 -resize 512x512 -strip "$out/icon-maskable-512.png"
