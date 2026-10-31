// Prints the text lines Vision finds in an image as JSON, with pixel bounds (top left origin).
import AppKit
import Foundation
import Vision

guard CommandLine.arguments.count >= 2,
      let image = NSImage(contentsOfFile: CommandLine.arguments[1]),
      let cgImage = image.cgImage(forProposedRect: nil, context: nil, hints: nil)
else {
    FileHandle.standardError.write("usage: ocr <image>\n".data(using: .utf8)!)
    exit(1)
}

let width = Double(cgImage.width)
let height = Double(cgImage.height)
let request = VNRecognizeTextRequest()
request.recognitionLevel = .accurate
request.usesLanguageCorrection = false

do {
    try VNImageRequestHandler(cgImage: cgImage, options: [:]).perform([request])
} catch {
    FileHandle.standardError.write("ocr failed: \(error)\n".data(using: .utf8)!)
    exit(1)
}

var lines: [[String: Any]] = []
for observation in request.results ?? [] {
    guard let text = observation.topCandidates(1).first?.string else { continue }
    let box = observation.boundingBox
    lines.append([
        "text": text,
        "left": box.minX * width,
        "right": box.maxX * width,
        "top": (1 - box.maxY) * height,
        "bottom": (1 - box.minY) * height,
    ])
}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: lines))
