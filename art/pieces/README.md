# Board game pieces

## Chess pieces
"Cburnett" style, from [Wikimedia Commons](https://commons.wikimedia.org/wiki/Category:SVG_chess_pieces),
by [Cburnett](https://en.wikipedia.org/wiki/User:Cburnett/GFDL_images/Chess). GFDL and CC BY-SA 3.0.

Naming: `Chess_<piece><color>t45.svg` — piece `p n b r q k`, color `l` (white) / `d` (black).
Converted to `app/src/main/res/drawable/piece_<color><piece>.xml`.

## Draughts stones
By [Antonsusi](https://commons.wikimedia.org/wiki/User:Antonsusi), from
[Wikimedia Commons](https://commons.wikimedia.org/wiki/Category:SVG_Draughts_pieces). Public domain.

Naming: `Chess_<1|2><color>t45.svg` — `1` man, `2` king. Converted to `drawable/stone_<color><1|2>.xml`.

## Conversion
SVG → Android vector drawable with Android Studio's `Svg2Vector` (`plugins/android/lib/sdk-common.jar`).
