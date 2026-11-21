# Analyzes a PNG cropped from PatternActivity's coordinate pattern.
# Reports size, decoded screen origin (from the center pixel), and how many pixels deviate from the
# pattern expected at that origin (0 = exact crop, no foreign pixels such as overlays).
param([Parameter(Mandatory = $true)][string]$Image)
Add-Type -AssemblyName System.Drawing
Add-Type -TypeDefinition @'
using System; using System.Drawing; using System.Drawing.Imaging; using System.Runtime.InteropServices;
public static class Pat {
  public static string Run(string path) {
    using (var src = new Bitmap(path))
    using (var bmp = src.Clone(new Rectangle(0, 0, src.Width, src.Height), PixelFormat.Format32bppArgb)) {
      var d = bmp.LockBits(new Rectangle(0, 0, bmp.Width, bmp.Height), ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
      int[] px = new int[bmp.Width * bmp.Height];
      Marshal.Copy(d.Scan0, px, 0, px.Length); bmp.UnlockBits(d);
      Func<int,int,int> X = (p, q) => ((p >> 16) & 0xFF) | (((p & 0xFF) >> 4) << 8);
      Func<int,int,int> Y = (p, q) => ((p >> 8) & 0xFF) | ((p & 0x0F) << 8);
      // Decode the center pixel (corners may be system bars, not pattern).
      int cx = bmp.Width / 2, cy = bmp.Height / 2, pc = px[cy * bmp.Width + cx];
      int ox = X(pc, 0) - cx, oy = Y(pc, 0) - cy;
      long bad = 0; int firstBadX = -1, firstBadY = -1;
      for (int y = 0; y < bmp.Height; y++) for (int x = 0; x < bmp.Width; x++) {
        int p = px[y * bmp.Width + x];
        int ex = ox + x, ey = oy + y;
        int e = (0xFF << 24) | ((ex & 0xFF) << 16) | ((ey & 0xFF) << 8) | (((ex >> 8) << 4) | (ey >> 8));
        if (p != e) { bad++; if (firstBadX < 0) { firstBadX = x; firstBadY = y; } }
      }
      return String.Format("{{\"w\":{0},\"h\":{1},\"originX\":{2},\"originY\":{3},\"badPixels\":{4},\"firstBad\":[{5},{6}]}}",
        bmp.Width, bmp.Height, ox, oy, bad, firstBadX, firstBadY);
    }
  }
}
'@ -ReferencedAssemblies System.Drawing
[Pat]::Run((Resolve-Path $Image).Path)
