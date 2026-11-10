import AppKit

// Apply the native icon's smooth outer contour before exporting its size variants.
// The approved artwork stays in place; clipping removes stray alpha-edge pixels.
let source = NSImage(contentsOfFile: CommandLine.arguments[1])!
let size = 1024
let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size,
    bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
    colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
let graphics = NSGraphicsContext(bitmapImageRep: bitmap)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = graphics
graphics.imageInterpolation = .high
let contour = NSBezierPath(roundedRect: NSRect(x: 100, y: 108, width: 824, height: 816),
    xRadius: 192, yRadius: 192)
contour.addClip()
NSColor(calibratedRed: 0.98, green: 0.967, blue: 0.953, alpha: 1).setFill()
contour.fill()
source.draw(in: NSRect(x: 0, y: 0, width: size, height: size),
    from: .zero, operation: .sourceOver, fraction: 1)
NSGraphicsContext.restoreGraphicsState()
try bitmap.representation(using: .png, properties: [:])!.write(
    to: URL(fileURLWithPath: CommandLine.arguments[2]))
