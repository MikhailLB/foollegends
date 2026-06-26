Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$src = "C:\Users\lobod\.cursor\projects\e-flutter-projects-FoolLegends\assets\c__Users_lobod_AppData_Roaming_Cursor_User_workspaceStorage_3a3444354dcdbc6d6262a0750b7efa2a_images_icon__67_-cc6fcf1b-038d-4c84-ab94-c8654298badd.png"

# Keep a clean copy + the full-bleed adaptive background (re-saved without ICC/metadata).
Copy-Item $src (Join-Path $root "assets\app_icon.png") -Force

$img = [System.Drawing.Image]::FromFile($src)

function Save-Clean([System.Drawing.Bitmap]$bmp, [string]$path) {
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
}

function New-Square([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.DrawImage($img, 0, 0, $size, $size)
    $g.Dispose()
    return $bmp
}

function New-Round([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $clip = New-Object System.Drawing.Drawing2D.GraphicsPath
    $clip.AddEllipse(0, 0, $size, $size)
    $g.SetClip($clip)
    $g.DrawImage($img, 0, 0, $size, $size)
    $g.Dispose()
    return $bmp
}

# Full-bleed adaptive background (108dp baseline → use 432 px source, re-saved clean).
$full = New-Square 512
Save-Clean $full (Join-Path $root "app\src\main\res\drawable\ic_launcher_full.png")
$full.Dispose()

$densities = @{ "mdpi"=48; "hdpi"=72; "xhdpi"=96; "xxhdpi"=144; "xxxhdpi"=192 }
foreach ($d in $densities.Keys) {
    $size = $densities[$d]
    $dir = Join-Path $root "app\src\main\res\mipmap-$d"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null

    $sq = New-Square $size
    Save-Clean $sq (Join-Path $dir "ic_launcher.png")
    $sq.Dispose()

    $rn = New-Round $size
    Save-Clean $rn (Join-Path $dir "ic_launcher_round.png")
    $rn.Dispose()
    Write-Host "mipmap-$d ($size px) done"
}

$img.Dispose()
Write-Host "Done."
