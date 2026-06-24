Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root "assets\jocker_normal.png"
$joker = [System.Drawing.Image]::FromFile($src)

$densities = @{
    "mdpi"    = 48
    "hdpi"    = 72
    "xhdpi"   = 96
    "xxhdpi"  = 144
    "xxxhdpi" = 192
}

function New-Icon([int]$size, [bool]$round) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic

    if ($round) {
        $clip = New-Object System.Drawing.Drawing2D.GraphicsPath
        $clip.AddEllipse(0, 0, $size, $size)
        $g.SetClip($clip)
    }

    # Deep-red radial-ish background.
    $rect = New-Object System.Drawing.Rectangle(0, 0, $size, $size)
    $bg = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        $rect,
        [System.Drawing.Color]::FromArgb(255, 126, 16, 21),
        [System.Drawing.Color]::FromArgb(255, 44, 7, 9),
        90.0)
    $g.FillRectangle($bg, $rect)

    # Joker centered, fit ~ 84% of canvas.
    $target = [int]($size * 0.92)
    $ratio = [Math]::Min($target / $joker.Width, $target / $joker.Height)
    $dw = [int]($joker.Width * $ratio)
    $dh = [int]($joker.Height * $ratio)
    $dx = [int](($size - $dw) / 2)
    $dy = [int](($size - $dh) / 2)
    $g.DrawImage($joker, $dx, $dy, $dw, $dh)

    $g.Dispose()
    return $bmp
}

foreach ($d in $densities.Keys) {
    $size = $densities[$d]
    $dir = Join-Path $root "app\src\main\res\mipmap-$d"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null

    $sq = New-Icon $size $false
    $sq.Save((Join-Path $dir "ic_launcher.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $sq.Dispose()

    $rnd = New-Icon $size $true
    $rnd.Save((Join-Path $dir "ic_launcher_round.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $rnd.Dispose()

    Write-Host "Generated mipmap-$d ($size px)"
}

$joker.Dispose()
Write-Host "Done."
