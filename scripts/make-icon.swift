import AppKit

// Render at the requested pixel size so every icon representation stays crisp.
guard CommandLine.arguments.count >= 2 else {
    fputs("Usage: make-icon.swift OUTPUT.png [SIZE]\n", stderr)
    exit(1)
}
let pixels = CommandLine.arguments.count > 2 ? Int(CommandLine.arguments[2]) ?? 0 : 1024
guard (16...1024).contains(pixels),
      let bitmap = NSBitmapImageRep(
          bitmapDataPlanes: nil, pixelsWide: pixels, pixelsHigh: pixels,
          bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true,
          isPlanar: false, colorSpaceName: .deviceRGB,
          bytesPerRow: 0, bitsPerPixel: 0),
      let context = NSGraphicsContext(bitmapImageRep: bitmap) else {
    fputs("Cannot create the requested icon bitmap.\n", stderr)
    exit(1)
}

func color(_ red: CGFloat, _ green: CGFloat, _ blue: CGFloat, _ alpha: CGFloat = 1) -> NSColor {
    NSColor(srgbRed: red, green: green, blue: blue, alpha: alpha)
}

NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = context
context.cgContext.scaleBy(x: CGFloat(pixels) / 1024, y: CGFloat(pixels) / 1024)
context.imageInterpolation = .high
context.shouldAntialias = true

// A quiet macOS tile gives the paired document mark room to read at Dock sizes.
let tile = NSBezierPath(roundedRect: NSRect(x: 80, y: 80, width: 864, height: 864),
                        xRadius: 194, yRadius: 194)
NSGraphicsContext.saveGraphicsState()
let tileShadow = NSShadow()
tileShadow.shadowColor = color(0.025, 0.035, 0.10, 0.18)
tileShadow.shadowOffset = NSSize(width: 0, height: -10)
tileShadow.shadowBlurRadius = 18
tileShadow.set()
color(0.10, 0.14, 0.34).setFill()
tile.fill()
NSGraphicsContext.restoreGraphicsState()
NSGradient(colors: [color(0.16, 0.23, 0.49), color(0.09, 0.12, 0.31), color(0.20, 0.16, 0.43)])!
    .draw(in: tile, angle: -52)
color(0.53, 0.66, 0.95, 0.20).setStroke()
tile.lineWidth = 2
tile.stroke()

// Two overlapping sheets remain recognizable without a file extension or text.
let rear = NSBezierPath(roundedRect: NSRect(x: 254, y: 270, width: 440, height: 558),
                        xRadius: 49, yRadius: 49)
NSGradient(colors: [color(0.43, 0.94, 0.91), color(0.16, 0.68, 0.82)])!
    .draw(in: rear, angle: -90)

let paper = NSBezierPath()
paper.move(to: NSPoint(x: 381, y: 204))
paper.line(to: NSPoint(x: 729, y: 204))
paper.curve(to: NSPoint(x: 778, y: 253), controlPoint1: NSPoint(x: 756, y: 204),
            controlPoint2: NSPoint(x: 778, y: 226))
paper.line(to: NSPoint(x: 778, y: 650))
paper.line(to: NSPoint(x: 666, y: 762))
paper.line(to: NSPoint(x: 381, y: 762))
paper.curve(to: NSPoint(x: 332, y: 713), controlPoint1: NSPoint(x: 354, y: 762),
            controlPoint2: NSPoint(x: 332, y: 740))
paper.line(to: NSPoint(x: 332, y: 253))
paper.curve(to: NSPoint(x: 381, y: 204), controlPoint1: NSPoint(x: 332, y: 226),
            controlPoint2: NSPoint(x: 354, y: 204))
paper.close()
NSGraphicsContext.saveGraphicsState()
let paperShadow = NSShadow()
paperShadow.shadowColor = color(0.02, 0.04, 0.14, 0.26)
paperShadow.shadowOffset = NSSize(width: 0, height: -12)
paperShadow.shadowBlurRadius = 22
paperShadow.set()
color(0.96, 0.98, 1).setFill()
paper.fill()
NSGraphicsContext.restoreGraphicsState()
NSGradient(colors: [color(1, 1, 1), color(0.91, 0.95, 1)])!
    .draw(in: paper, angle: -90)

// The folded corner is a single broad plane, visible even in a small icon.
let fold = NSBezierPath()
fold.move(to: NSPoint(x: 666, y: 762))
fold.line(to: NSPoint(x: 666, y: 681))
fold.curve(to: NSPoint(x: 697, y: 650), controlPoint1: NSPoint(x: 666, y: 664),
           controlPoint2: NSPoint(x: 680, y: 650))
fold.line(to: NSPoint(x: 778, y: 650))
fold.close()
NSGradient(colors: [color(0.75, 0.85, 0.99), color(0.56, 0.72, 0.94)])!
    .draw(in: fold, angle: -45)

// Opposing arrows describe conversion in both directions with one simple mark.
func arrow(from start: NSPoint, to end: NSPoint, fill: NSColor) {
    let direction: CGFloat = end.x > start.x ? 1 : -1
    let path = NSBezierPath()
    path.lineWidth = 46
    path.lineCapStyle = .round
    path.lineJoinStyle = .round
    path.move(to: start)
    path.line(to: end)
    path.move(to: NSPoint(x: end.x - direction * 62, y: end.y + 62))
    path.line(to: end)
    path.line(to: NSPoint(x: end.x - direction * 62, y: end.y - 62))
    fill.setStroke()
    path.stroke()
}
arrow(from: NSPoint(x: 423, y: 549), to: NSPoint(x: 687, y: 549), fill: color(0.23, 0.28, 0.58))
arrow(from: NSPoint(x: 687, y: 407), to: NSPoint(x: 423, y: 407), fill: color(0.08, 0.59, 0.68))

context.flushGraphics()
NSGraphicsContext.restoreGraphicsState()
guard let png = bitmap.representation(using: .png, properties: [:]) else {
    fputs("Cannot encode the icon PNG.\n", stderr)
    exit(1)
}
try png.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
