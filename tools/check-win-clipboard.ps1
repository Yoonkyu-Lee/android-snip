# Waits for an image on the Windows clipboard whose pixels match -Expected (a PNG).
# Compares RGB only (clipboard DIBs often drop alpha). Prints one JSON line.
param(
    [Parameter(Mandatory = $true)][string]$Expected,
    [int]$TimeoutSec = 30
)
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
Add-Type -Namespace W -Name U -MemberDefinition '[DllImport("user32.dll")] public static extern uint GetClipboardSequenceNumber();'

function Get-RgbHash([System.Drawing.Image]$img) {
    $bmp = New-Object System.Drawing.Bitmap $img.Width, $img.Height, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.DrawImage($img, 0, 0, $img.Width, $img.Height)
    $g.Dispose()
    $rect = New-Object System.Drawing.Rectangle 0, 0, $bmp.Width, $bmp.Height
    $data = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly, $bmp.PixelFormat)
    $n = $data.Stride * $bmp.Height
    $buf = New-Object byte[] $n
    [System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $buf, 0, $n)
    $bmp.UnlockBits($data); $bmp.Dispose()
    for ($i = 3; $i -lt $n; $i += 4) { $buf[$i] = 0 }   # ignore alpha
    $sha = [System.Security.Cryptography.SHA256]::Create()
    return ([BitConverter]::ToString($sha.ComputeHash($buf)) -replace '-', '').ToLower()
}

$exp = [System.Drawing.Image]::FromFile((Resolve-Path $Expected))
$expHash = Get-RgbHash $exp
$expSize = "$($exp.Width)x$($exp.Height)"
$exp.Dispose()

$start = Get-Date
$lastSeq = 0; $last = $null
while (((Get-Date) - $start).TotalSeconds -lt $TimeoutSec) {
    $seq = [W.U]::GetClipboardSequenceNumber()
    if ($seq -ne $lastSeq) {
        $lastSeq = $seq
        try {
            $formats = [System.Windows.Forms.Clipboard]::GetDataObject().GetFormats() -join ','
            $img = $null
            # Prefer a PNG stream if present (lossless, keeps exact pixels), else the DIB.
            $png = [System.Windows.Forms.Clipboard]::GetData('PNG')
            if ($png -is [System.IO.MemoryStream]) { $img = [System.Drawing.Image]::FromStream($png) }
            elseif ([System.Windows.Forms.Clipboard]::ContainsImage()) { $img = [System.Windows.Forms.Clipboard]::GetImage() }
            if ($img) {
                $h = Get-RgbHash $img
                $last = @{ size = "$($img.Width)x$($img.Height)"; hash = $h; formats = $formats }
                $img.Dispose()
                if ($h -eq $expHash) {
                    @{ match = $true; seconds = [math]::Round(((Get-Date) - $start).TotalSeconds, 1); expected = $expSize; got = $last } | ConvertTo-Json -Compress
                    exit 0
                }
            } else { $last = @{ formats = $formats } }
        } catch { $last = @{ error = $_.Exception.Message } }
    }
    Start-Sleep -Milliseconds 300
}
@{ match = $false; expected = $expSize; expectedHash = $expHash; last = $last } | ConvertTo-Json -Compress
exit 1
