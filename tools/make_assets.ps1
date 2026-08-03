Add-Type -AssemblyName System.Drawing

# Rebuilds the shipped artwork from the sources in assets/:
#   assets/loading_screen_vertical.jpg   -> res/drawable-nodpi/loading_portrait.jpg
#   assets/loading_screen_horizontal.jpg -> res/drawable-nodpi/loading_landscape.jpg
#   assets/white_icon.jpg                -> adaptive + legacy launcher icons

$root = Split-Path -Parent $PSScriptRoot
$res = Join-Path $root "app\src\main\res"
$nodpi = Join-Path $res "drawable-nodpi"
New-Item -ItemType Directory -Force -Path $nodpi | Out-Null

# --- Loading art -------------------------------------------------------------
# Kept as JPEG: the art is photographic, PNG would add ~6 MB to the APK.
Get-ChildItem -Path $nodpi -Filter "loading_*.*" -File | Remove-Item -Force
Copy-Item (Join-Path $root "assets\loading_screen_vertical.jpg") (Join-Path $nodpi "loading_portrait.jpg") -Force
Copy-Item (Join-Path $root "assets\loading_screen_horizontal.jpg") (Join-Path $nodpi "loading_landscape.jpg") -Force
Write-Host "Loading art copied."

# --- Launcher icon -----------------------------------------------------------
$src = [System.Drawing.Bitmap]::new((Join-Path $root "assets\white_icon.jpg"))

# The source is glowing art on black. Turn the black into transparency so the
# adaptive background shows through, and remember the bounds of what is left.
$master = New-Object System.Drawing.Bitmap($src.Width, $src.Height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$rect = New-Object System.Drawing.Rectangle(0, 0, $src.Width, $src.Height)
$sData = $src.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$mData = $master.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::WriteOnly, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$count = $src.Width * $src.Height * 4
$buf = New-Object byte[] $count
[System.Runtime.InteropServices.Marshal]::Copy($sData.Scan0, $buf, 0, $count)

# Alpha ramp over luminance: below $lo is pure background, above $hi is art.
# The gap swallows JPEG noise in the dark areas without eating the soft glow.
$lo = 10.0
$hi = 34.0
$minX = $src.Width; $minY = $src.Height; $maxX = -1; $maxY = -1
for ($y = 0; $y -lt $src.Height; $y++) {
    $row = $y * $sData.Stride
    for ($x = 0; $x -lt $src.Width; $x++) {
        $i = $row + $x * 4
        $lum = 0.114 * $buf[$i] + 0.587 * $buf[$i + 1] + 0.299 * $buf[$i + 2]
        $a = [Math]::Round(255.0 * [Math]::Min(1.0, [Math]::Max(0.0, ($lum - $lo) / ($hi - $lo))))
        $buf[$i + 3] = [byte]$a
        if ($a -gt 8) {
            if ($x -lt $minX) { $minX = $x }
            if ($x -gt $maxX) { $maxX = $x }
            if ($y -lt $minY) { $minY = $y }
            if ($y -gt $maxY) { $maxY = $y }
        }
    }
}
[System.Runtime.InteropServices.Marshal]::Copy($buf, 0, $mData.Scan0, $count)
$src.UnlockBits($sData)
$master.UnlockBits($mData)
$src.Dispose()

$art = New-Object System.Drawing.Rectangle($minX, $minY, ($maxX - $minX + 1), ($maxY - $minY + 1))
Write-Host "Art bounds: $($art.X),$($art.Y) $($art.Width)x$($art.Height)"

# Draws the art centred inside a square canvas, scaled to $fill of the side.
function Add-Art([System.Drawing.Bitmap]$bmp, [double]$fill, [bool]$circle = $false) {
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    if ($circle) {
        $path = New-Object System.Drawing.Drawing2D.GraphicsPath
        $path.AddEllipse(0, 0, $bmp.Width, $bmp.Height)
        $g.SetClip($path)
        $path.Dispose()
    }
    $target = $bmp.Width * $fill
    $ratio = [Math]::Min($target / $art.Width, $target / $art.Height)
    $dw = $art.Width * $ratio
    $dh = $art.Height * $ratio
    $dst = New-Object System.Drawing.RectangleF(
        [float](($bmp.Width - $dw) / 2), [float](($bmp.Height - $dh) / 2), [float]$dw, [float]$dh)
    $g.DrawImage($master, $dst, $art, [System.Drawing.GraphicsUnit]::Pixel)
    $g.Dispose()
}

function New-Canvas([int]$size, $color) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.Clear($color)
    $g.Dispose()
    return $bmp
}

$black = [System.Drawing.Color]::FromArgb(255, 0, 0, 0)
# Adaptive canvas is 108dp with a 66dp safe zone; legacy icons are 48dp.
$densities = @{ "mdpi" = 1; "hdpi" = 1.5; "xhdpi" = 2; "xxhdpi" = 3; "xxxhdpi" = 4 }

foreach ($d in $densities.Keys) {
    $dir = Join-Path $res "mipmap-$d"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $adaptive = [int](108 * $densities[$d])
    $legacy = [int](48 * $densities[$d])

    # Adaptive foreground: everything inside the safe zone, so no mask can clip it.
    $fg = New-Canvas $adaptive ([System.Drawing.Color]::Transparent)
    Add-Art $fg (66.0 / 108.0)
    $fg.Save((Join-Path $dir "ic_launcher_foreground.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $fg.Dispose()

    # Legacy square icon: full art on the original black.
    $sq = New-Canvas $legacy $black
    Add-Art $sq 1.0
    $sq.Save((Join-Path $dir "ic_launcher.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $sq.Dispose()

    # Legacy round icon: black disc, art inset to stay inside the circle.
    $rn = New-Canvas $legacy ([System.Drawing.Color]::Transparent)
    $g = [System.Drawing.Graphics]::FromImage($rn)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $brush = New-Object System.Drawing.SolidBrush($black)
    $g.FillEllipse($brush, 0, 0, $legacy - 1, $legacy - 1)
    $brush.Dispose()
    $g.Dispose()
    Add-Art $rn 0.92 $true
    $rn.Save((Join-Path $dir "ic_launcher_round.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $rn.Dispose()

    Write-Host "mipmap-$d : foreground $adaptive px, legacy $legacy px"
}

$master.Dispose()
Write-Host "Done."
