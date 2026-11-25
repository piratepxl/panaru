// swift-tools-version:5.10
// Panaru per macOS — costruibile con i soli Command Line Tools (`swift build`).
// Il bundle .app viene assemblato da scripts/build-app.sh.
import PackageDescription

let package = Package(
    name: "Panaru",
    platforms: [.macOS(.v14)],
    products: [
        .executable(name: "Panaru", targets: ["Panaru"]),
        .library(name: "PanaruKit", targets: ["PanaruKit"]),
    ],
    targets: [
        .target(name: "PanaruKit"),
        .executableTarget(name: "Panaru", dependencies: ["PanaruKit"]),
        .testTarget(name: "PanaruKitTests", dependencies: ["PanaruKit"]),
    ]
)
