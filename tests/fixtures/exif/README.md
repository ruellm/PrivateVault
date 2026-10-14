# EXIF orientation fixtures (Stage 3B)

TEST ONLY. Synthetic images; nothing here is a secret or a capture.

- `base.jpg`: 64x32 pixels, grey (128, 128, 128) with a 16x16 red square (220, 30, 30) at the
  top left, JPEG quality 95, no EXIF. Made once on Windows with .NET `System.Drawing`:

  ```
  Add-Type -AssemblyName System.Drawing
  $bmp = New-Object System.Drawing.Bitmap 64,32
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.Clear([System.Drawing.Color]::FromArgb(128,128,128))
  $g.FillRectangle((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(220,30,30))), 0, 0, 16, 16)
  $codec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
  $params = New-Object System.Drawing.Imaging.EncoderParameters 1
  $params.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter ([System.Drawing.Imaging.Encoder]::Quality, [long]95)
  $bmp.Save('tests\fixtures\exif\base.jpg', $codec, $params)
  ```

- `orient_1.jpg`, `orient_3.jpg`, `orient_6.jpg`, `orient_8.jpg`: `base.jpg` with a hand-built
  APP1 Exif segment after SOI carrying the Orientation tag (0x0112) with that value
  (1 upright, 3 turned 180, 6 turned 90 clockwise, 8 turned 270 clockwise). Written by
  `tests/tools/make_exif_fixtures.py tests/fixtures/exif`.

The instrumented test `ViewerLoaderTest` wraps each fixture in an item header with rotation
0, 90, 180, and 270 and checks where the red square lands after the orientation rule of
`docs/PHASE3B_DESIGN.md` Section 5.2. The committed files are the reference.
