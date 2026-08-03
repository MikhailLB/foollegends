# Regenerates all Android launcher icon assets from assets/icon3.jpg.
#
# Goal: the artwork must fill the ENTIRE icon canvas edge-to-edge (no padding,
# no "safe zone" shrink), so that whatever mask shape a launcher applies
# (circle, squircle, rounded square, teardrop...) is inscribed IN the square
# canvas and crops as little of the art as possible - never the other way
# around (square shrunk to fit inside a circle).
#
# Produces, for every density:
#   mipmap-<d>/ic_launcher.png        - legacy square icon (pre-API26 launchers)
#   mipmap-<d>/ic_launcher_round.png  - legacy round icon: circle inscribed in
#                                        the same full-bleed square (only the
#                                        4 corners are cropped - unavoidable
#                                        for a round shape, nothing else lost)
#   mipmap-<d>/ic_launcher_layer.png  - adaptive-icon background layer
#                                        (108dp canvas), full bleed. Foreground
#                                        stays fully transparent (ic_launcher_veil)
#                                        so API26+ launchers show the same
#                                        full-bleed art, whatever mask they use.

Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root "assets\icon3.jpg"
$img = [System.Drawing.Image]::FromFile($src)

if ($img.Width -ne $img.Height) {
    Write-Warning "Source is not square ($($img.Width)x$($img.Height)) - it will be stretched to fill the square canvas."
}

# Legacy launcher icon sizes (px) and adaptive-icon layer sizes (108dp base).
$legacySizes  = @{ "mdpi"=48;  "hdpi"=72;  "xhdpi"=96;  "xxhdpi"=144; "xxxhdpi"=192 }
$layerSizes   = @{ "mdpi"=108; "hdpi"=162; "xhdpi"=216; "xxhdpi"=324; "xxxhdpi"=432 }

function New-Canvas([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    return @{ Bmp = $bmp; G = $g }
}

function New-SquareFullBleed([int]$size) {
    $c = New-Canvas $size
    # Full-bleed: stretch the source to cover the entire square, no insets.
    $c.G.DrawImage($img, 0, 0, $size, $size)
    $c.G.Dispose()
    return $c.Bmp
}

function New-RoundFullBleed([int]$size) {
    $c = New-Canvas $size
    $clip = New-Object System.Drawing.Drawing2D.GraphicsPath
    # Circle inscribed in the square: touches all 4 edge midpoints.
    $clip.AddEllipse(0, 0, $size, $size)
    $c.G.SetClip($clip)
    $c.G.DrawImage($img, 0, 0, $size, $size)
    $c.G.Dispose()
    return $c.Bmp
}

foreach ($d in $legacySizes.Keys) {
    $dir = Join-Path $root "app\src\main\res\mipmap-$d"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null

    $size = $legacySizes[$d]
    $sq = New-SquareFullBleed $size
    $sq.Save((Join-Path $dir "ic_launcher.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $sq.Dispose()

    $rnd = New-RoundFullBleed $size
    $rnd.Save((Join-Path $dir "ic_launcher_round.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $rnd.Dispose()

    $lsize = $layerSizes[$d]
    $layer = New-SquareFullBleed $lsize
    $layer.Save((Join-Path $dir "ic_launcher_layer.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $layer.Dispose()

    Write-Host "mipmap-$d : ic_launcher $size px, ic_launcher_round $size px, ic_launcher_layer $lsize px"
}

$img.Dispose()
Write-Host "Done."
