# Fixes over-cropping of the adaptive icon on real devices.
#
# WHY the icon looked too cropped even after making the background full-bleed:
# Android's AdaptiveIconDrawable does NOT show a 108x108dp layer 1:1. It always
# scales/crops so that only the CENTER 72x72dp (=66.7%) of the 108dp canvas is
# ever visible in the static icon - the outer 18dp ring on every side is
# reserved for parallax/pulse motion and is normally invisible
# (see DEFAULT_VIEW_PORT_SCALE = 1 / (1 + 2*0.25) = 0.667 in
# AdaptiveIconDrawable.java). A background that fills the full 108dp canvas
# therefore gets a SECOND, hidden 33% crop from the OS on top of the mask
# shape's own corner-cropping - which is why the joker's face looked much more
# zoomed in than the source image.
#
# FIX: draw the full artwork (same full-bleed "cover" logic as the background)
# into the FOREGROUND layer, but sized to exactly fill that inner 72dp
# viewport (66.7% of the 108dp canvas) instead of the full canvas, with a
# transparent margin around it. That way:
#   - The OS's automatic center-crop-to-72dp reveals the artwork FULLY
#     (nothing extra lost), so the only remaining crop is the mask shape
#     itself (e.g. a circle inscribed in that 72dp square - same "circle in
#     square, not square in circle" cropping as before, just correctly scaled).
#   - The transparent margin lets the full-bleed background layer
#     (ic_launcher_layer.png, unchanged) show through in the outer ring, so
#     nothing ever looks empty/blank during parallax reveals - it's just a
#     more zoomed-in continuation of the very same photo.

Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root "assets\icon3.jpg"
$img = [System.Drawing.Image]::FromFile($src)

# 108dp adaptive-icon canvas sizes per density, and the safe inner viewport
# (72/108 = 2/3 of the canvas) that is actually visible in the static icon.
$canvasSizes = @{ "mdpi"=108; "hdpi"=162; "xhdpi"=216; "xxhdpi"=324; "xxxhdpi"=432 }

foreach ($d in $canvasSizes.Keys) {
    $n = $canvasSizes[$d]
    $inner = [int][Math]::Round($n * 2.0 / 3.0)
    $offset = [int](($n - $inner) / 2)

    $bmp = New-Object System.Drawing.Bitmap($n, $n)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $g.Clear([System.Drawing.Color]::Transparent)
    # Full-bleed within the inner viewport only - transparent margin outside.
    $g.DrawImage($img, $offset, $offset, $inner, $inner)
    $g.Dispose()

    $dir = Join-Path $root "app\src\main\res\drawable-$d"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $path = Join-Path $dir "ic_launcher_veil.png"
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "drawable-$d/ic_launcher_veil.png : canvas $n px, inner viewport $inner px"
}

$img.Dispose()
Write-Host "Done."
