Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root "assets\jocker_normal.png"
$joker = [System.Drawing.Image]::FromFile($src)

# Adaptive icon canvas is 108dp; keep art inside the ~66dp safe zone.
$densities = @{ "mdpi"=108; "hdpi"=162; "xhdpi"=216; "xxhdpi"=324; "xxxhdpi"=432 }

foreach ($d in $densities.Keys) {
    $size = $densities[$d]
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.Clear([System.Drawing.Color]::Transparent)

    $target = [int]($size * 0.62)
    $ratio = [Math]::Min($target / $joker.Width, $target / $joker.Height)
    $dw = [int]($joker.Width * $ratio)
    $dh = [int]($joker.Height * $ratio)
    $dx = [int](($size - $dw) / 2)
    $dy = [int](($size - $dh) / 2)
    $g.DrawImage($joker, $dx, $dy, $dw, $dh)
    $g.Dispose()

    $dir = Join-Path $root "app\src\main\res\mipmap-$d"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $bmp.Save((Join-Path $dir "ic_launcher_foreground.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "Foreground mipmap-$d ($size px)"
}

$joker.Dispose()
Write-Host "Done."
