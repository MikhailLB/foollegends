Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$src = "C:\Users\lobod\.cursor\projects\e-flutter-projects-FoolLegends\assets\c__Users_lobod_AppData_Roaming_Cursor_User_workspaceStorage_eef92b73b0fd7730443033d6ba6cc156_images_icon-001c443b-788b-47ec-8f88-9e643d454f2e.png"

# Keep a copy in the project + the full-bleed adaptive background.
Copy-Item $src (Join-Path $root "assets\app_icon.png") -Force
$nodpi = Join-Path $root "app\src\main\res\drawable-nodpi"
New-Item -ItemType Directory -Force -Path $nodpi | Out-Null
Copy-Item $src (Join-Path $nodpi "ic_launcher_full.png") -Force

$img = [System.Drawing.Image]::FromFile($src)
$densities = @{ "mdpi"=48; "hdpi"=72; "xhdpi"=96; "xxhdpi"=144; "xxxhdpi"=192 }

function New-Square([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.DrawImage($img, 0, 0, $size, $size)
    $g.Dispose()
    return $bmp
}

function New-Round([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
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

foreach ($d in $densities.Keys) {
    $size = $densities[$d]
    $dir = Join-Path $root "app\src\main\res\mipmap-$d"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null

    $sq = New-Square $size
    $sq.Save((Join-Path $dir "ic_launcher.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $sq.Dispose()

    $rn = New-Round $size
    $rn.Save((Join-Path $dir "ic_launcher_round.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $rn.Dispose()
    Write-Host "mipmap-$d ($size px) done"
}

$img.Dispose()
Write-Host "Done."
