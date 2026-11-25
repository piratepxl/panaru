// Rasterizza un SVG in PNG usando il renderer SVG di AppKit (macOS 14+).
import AppKit
// uso: r2 input.svg output.png larghezzaPx
let a = CommandLine.arguments
guard let img = NSImage(contentsOfFile: a[1]) else { print("fail \(a[1])"); exit(1) }
let w = Int(a[3])!
let h = Int((Double(w) * img.size.height / img.size.width).rounded())
let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: w, pixelsHigh: h, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
rep.size = NSSize(width: w, height: h)
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
NSGraphicsContext.current?.imageInterpolation = .high
img.draw(in: NSRect(x: 0, y: 0, width: w, height: h))
NSGraphicsContext.restoreGraphicsState()
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: a[2]))
