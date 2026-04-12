# Generate Android launcher icons
Add-Type -AssemblyName System.Drawing

$basePath = "c:\Users\seanb\OneDrive\Documents\Antigravity\Android Application\ARLiveBriefInterview\app\src\main\res"

$sizes = @{
    "mipmap-mdpi" = 48
    "mipmap-hdpi" = 72
    "mipmap-xhdpi" = 96
    "mipmap-xxhdpi" = 144
    "mipmap-xxxhdpi" = 192
}

foreach ($entry in $sizes.GetEnumerator()) {
    $dir = Join-Path $basePath $entry.Key
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
    $s = $entry.Value
    $bmp = New-Object System.Drawing.Bitmap $s, $s
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.Clear([System.Drawing.Color]::FromArgb(255, 98, 0, 238))

    $fontSize = [Math]::Floor($s * 0.35)
    $font = New-Object System.Drawing.Font("Arial", $fontSize, [System.Drawing.FontStyle]::Bold)
    $brush = [System.Drawing.Brushes]::White
    $sf = New-Object System.Drawing.StringFormat
    $sf.Alignment = [System.Drawing.StringAlignment]::Center
    $sf.LineAlignment = [System.Drawing.StringAlignment]::Center
    $rect = New-Object System.Drawing.RectangleF(0, 0, $s, $s)
    $g.DrawString("AR", $font, $brush, $rect, $sf)

    $g.Dispose()
    $bmp.Save((Join-Path $dir "ic_launcher.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Save((Join-Path $dir "ic_launcher_round.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "Created icons for $($entry.Key) at ${s}x${s}"
}
Write-Host "All launcher icons created!"
