# Crops a PNG: crop-png.ps1 -In screen.png -Out crop.png -X 100 -Y 200 -W 300 -H 400
param([string]$In, [string]$Out, [int]$X, [int]$Y, [int]$W, [int]$H)
Add-Type -AssemblyName System.Drawing
$src = New-Object System.Drawing.Bitmap (Resolve-Path $In).Path
$dst = $src.Clone((New-Object System.Drawing.Rectangle $X, $Y, $W, $H), [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$dst.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$src.Dispose(); $dst.Dispose()
