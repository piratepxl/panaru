import Foundation
import Testing
@testable import PanaruKit

// MARK: - Server Gokapi simulato

/// Intercetta le richieste di una URLSession di test e risponde come Gokapi.
final class MockGokapi: URLProtocol, @unchecked Sendable {
    struct Recorded: Sendable {
        let method: String
        let path: String
        let headers: [String: String]
        let body: Data
        let host: String
    }

    nonisolated(unsafe) static var handler: (@Sendable (Recorded) -> (Int, Data))?
    nonisolated(unsafe) static var recorded: [Recorded] = []
    static let lock = NSLock()

    static func reset(_ h: @escaping @Sendable (Recorded) -> (Int, Data)) {
        lock.withLock { recorded = []; handler = h }
    }

    static var requests: [Recorded] { lock.withLock { recorded } }

    static func session() -> URLSession {
        let c = URLSessionConfiguration.ephemeral
        c.protocolClasses = [MockGokapi.self]
        return URLSession(configuration: c)
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        var body = request.httpBody ?? Data()
        if body.isEmpty, let s = request.httpBodyStream {
            s.open()
            var buf = [UInt8](repeating: 0, count: 1 << 16)
            while s.hasBytesAvailable {
                let n = s.read(&buf, maxLength: buf.count)
                if n <= 0 { break }
                body.append(buf, count: n)
            }
            s.close()
        }
        let rec = Recorded(
            method: request.httpMethod ?? "GET",
            path: request.url?.path ?? "",
            headers: request.allHTTPHeaderFields ?? [:],
            body: body,
            host: request.url?.host ?? ""
        )
        let (status, data) = MockGokapi.lock.withLock { () -> (Int, Data) in
            MockGokapi.recorded.append(rec)
            return MockGokapi.handler?(rec) ?? (500, Data())
        }
        let resp = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1",
                                   headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

extension MockGokapi.Recorded {
    /// Valore di un campo del corpo multipart.
    func field(_ name: String) -> String? {
        let text = String(decoding: body, as: UTF8.self)
        guard let r = text.range(of: "name=\"\(name)\"\r\n\r\n"),
              let end = text.range(of: "\r\n", range: r.upperBound..<text.endIndex) else { return nil }
        // Nota: in Swift "\r\n" è un solo Character, quindi si cerca la sottostringa.
        return String(text[r.upperBound..<end.lowerBound])
    }

    /// Byte della parte `file`.
    func filePart() -> Data {
        let marker = Data("Content-Type: application/octet-stream\r\n\r\n".utf8)
        guard let start = body.range(of: marker)?.upperBound,
              let end = body.range(of: Data("\r\n--Panaru".utf8), in: start..<body.endIndex)?.lowerBound else { return Data() }
        return body.subdata(in: start..<end)
    }
}

private func json(_ s: String) -> Data { Data(s.utf8) }

private func fileInfo(_ id: String = "abc123") -> Data {
    json(#"{"Result":"OK","FileInfo":{"Id":"\#(id)","Name":"a","SizeBytes":3,"UrlDownload":"https://g.example/d?id=\#(id)","ExpireAt":4000000000,"DownloadsRemaining":2,"Extra":1}}"#)
}

private func tempFile(_ bytes: Int, name: String = "test.bin") throws -> URL {
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("panaru-tests-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    let url = dir.appendingPathComponent(name)
    try Data((0..<bytes).map { UInt8($0 % 251) }).write(to: url)
    return url
}

private let fastRetry = GokapiClient.RetryPolicy(maxAttempts: 3, initialDelay: .milliseconds(1))

// MARK: - Test

@Suite(.serialized)
struct ServerURLTests {
    func valid(_ s: String) throws -> ServerURL.Valid { try ServerURL.parse(s).get() }
    func invalid(_ s: String) -> ServerURL.Invalid? {
        if case .failure(let e) = ServerURL.parse(s) { return e }
        return nil
    }

    @Test func addsHttpsAndStripsSuffixes() throws {
        #expect(try valid("files.example.com").canonical == "https://files.example.com")
        #expect(try valid("https://x.example/gokapi/admin?x=1#f").canonical == "https://x.example/gokapi")
        #expect(try valid("https://x.example/d?id=abc").canonical == "https://x.example")
    }

    @Test func httpOnlyForLocalHosts() throws {
        #expect(try valid("http://192.168.1.10:53842").isInsecureLocal)
        #expect(try valid("http://nas.local").isInsecureLocal)
        #expect(invalid("http://files.example.com") == .insecurePublicHost)
        #expect(invalid("http://8.8.8.8") == .insecurePublicHost)
    }

    @Test func rejectsBadInput() {
        #expect(invalid("  ") == .empty)
        #expect(invalid("ftp://x.example") == .unsupportedScheme)
        #expect(invalid("https://u:p@x.example") == .credentialsInURL)
    }

    @Test func chunkPlanning() {
        let mb = ChunkPlanner.mb
        #expect(ChunkPlanner.chunkSize(nil) == 45 * mb)
        #expect(ChunkPlanner.chunkSize(ServerConfig(maxFileSizeMB: 1000, maxChunkSizeMB: 1)) == 5 * mb)
        #expect(ChunkPlanner.chunkSize(ServerConfig(maxFileSizeMB: 30, maxChunkSizeMB: 45)) < 30 * mb)
        let plan = ChunkPlanner.plan(size: 11 * mb + 3, chunkSize: 5 * mb)
        #expect(plan.map(\.offset) == [0, 5 * mb, 10 * mb])
        #expect(plan.reduce(0) { $0 + $1.length } == 11 * mb + 3)
        #expect(ChunkPlanner.plan(size: 0, chunkSize: mb) == [.init(offset: 0, length: 0)])
    }

    @Test func fileNameEncoding() {
        #expect(ChunkPlanner.encodeFileName("report.pdf") == "report.pdf")
        let enc = ChunkPlanner.encodeFileName("Città 📷.jpg")
        #expect(enc.hasPrefix("base64:"))
        #expect(String(data: Data(base64Encoded: String(enc.dropFirst(7)))!, encoding: .utf8) == "Città 📷.jpg")
        #expect(UploadOptions.isValidPassword("abc 1!~"))
        #expect(!UploadOptions.isValidPassword("pàss"))
    }
}

@Suite(.serialized)
struct GokapiClientTests {
    let client = GokapiClient(baseURL: URL(string: "https://g.example/sub/")!, apiKey: "k1", session: MockGokapi.session(), retry: fastRetry)

    @Test func versionUsesApiKeyAndPath() async throws {
        MockGokapi.reset { _ in (200, json(#"{"Version":"2.2.4","VersionInt":20204}"#)) }
        let v = try await client.version()
        #expect(v.versionInt == 20204 && v.supportsBase64FileNames)
        let r = MockGokapi.requests[0]
        #expect(r.path == "/sub/api/info/version")
        #expect(r.headers["apikey"] == "k1")
    }

    @Test func smallUploadSendsChunkAndCompleteHeaders() async throws {
        MockGokapi.reset { r in r.path.hasSuffix("chunk/add") ? (200, json(#"{"result":"OK"}"#)) : (200, fileInfo()) }
        let url = try tempFile(3, name: "Città.txt")
        let file = try await client.upload(fileURL: url, mimeType: "text/plain",
                                           options: UploadOptions(expiryDays: 7, allowedDownloads: 0, password: "pw"),
                                           config: ServerConfig(maxFileSizeMB: 100, maxChunkSizeMB: 45))
        #expect(file.id == "abc123")
        let reqs = MockGokapi.requests
        #expect(reqs.count == 2)
        let add = reqs[0]
        #expect(add.field("filesize") == "3")
        #expect(add.field("offset") == "0")
        #expect((add.field("uuid")?.count ?? 0) >= 10)
        #expect(add.filePart() == (try Data(contentsOf: url)))
        let complete = reqs[1]
        #expect(complete.headers["uuid"] == add.field("uuid"))
        #expect(complete.headers["filename"]?.hasPrefix("base64:") == true)
        #expect(complete.headers["expiryDays"] == "7")
        #expect(complete.headers["allowedDownloads"] == "0")
        #expect(complete.headers["password"] == "pw")
        #expect(complete.headers["contenttype"] == "text/plain")
    }

    @Test func largeUploadIsChunked() async throws {
        MockGokapi.reset { r in r.path.hasSuffix("chunk/add") ? (200, json(#"{"result":"OK"}"#)) : (200, fileInfo()) }
        let size = Int(12 * ChunkPlanner.mb)
        let url = try tempFile(size)
        let original = try Data(contentsOf: url)
        _ = try await client.upload(fileURL: url, options: UploadOptions(), config: ServerConfig(maxFileSizeMB: 100, maxChunkSizeMB: 5))
        let adds = MockGokapi.requests.filter { $0.path.hasSuffix("chunk/add") }
        #expect(adds.compactMap { $0.field("offset") } == ["0", "\(5 * ChunkPlanner.mb)", "\(10 * ChunkPlanner.mb)"])
        var rebuilt = Data()
        for a in adds { rebuilt.append(a.filePart()) }
        #expect(rebuilt == original)
        let complete = MockGokapi.requests.last!
        #expect(complete.headers["expiryDays"] == "14")
        #expect(complete.headers["allowedDownloads"] == "1")
        #expect(complete.headers["password"] == nil)
    }

    @Test func retriesServerErrorsButNotClientErrors() async throws {
        let attempts = Counter()
        MockGokapi.reset { r in
            if r.path.hasSuffix("chunk/add") { return attempts.next() == 1 ? (500, Data()) : (200, json(#"{"result":"OK"}"#)) }
            return (200, fileInfo())
        }
        _ = try await client.upload(fileURL: try tempFile(10), options: UploadOptions(), config: nil)
        #expect(MockGokapi.requests.count == 3)

        MockGokapi.reset { _ in (401, json(#"{"Result":"error","ErrorMessage":"Unauthorized","ErrorCode":2}"#)) }
        await #expect(throws: GokapiError.unauthorized) {
            _ = try await client.upload(fileURL: try tempFile(10), options: UploadOptions(), config: nil)
        }
        #expect(MockGokapi.requests.count == 1)
    }

    @Test func serverMessageAndLimits() async throws {
        MockGokapi.reset { _ in (400, json(#"{"Result":"error","ErrorMessage":"chunk too small","ErrorCode":11}"#)) }
        await #expect(throws: GokapiError.server(status: 400, code: 11, message: "chunk too small")) {
            _ = try await client.upload(fileURL: try tempFile(10), options: UploadOptions(), config: nil)
        }
        MockGokapi.reset { _ in (200, Data()) }
        await #expect(throws: GokapiError.fileTooLarge(maxMB: 1)) {
            _ = try await client.upload(fileURL: try tempFile(Int(ChunkPlanner.mb) + 1), options: UploadOptions(),
                                        config: ServerConfig(maxFileSizeMB: 1, maxChunkSizeMB: 1))
        }
        #expect(MockGokapi.requests.isEmpty)
    }

    @Test func missingSourceIsReported() async {
        MockGokapi.reset { _ in (200, Data()) }
        await #expect(throws: GokapiError.self) {
            _ = try await client.upload(fileURL: URL(fileURLWithPath: "/nonexistent/x"), options: UploadOptions(), config: nil)
        }
    }

    @Test func listDeleteAndProtocolErrors() async throws {
        MockGokapi.reset { _ in (200, json("null")) }
        #expect(try await client.listFiles().isEmpty)
        MockGokapi.reset { r in r.method == "DELETE" ? (200, Data()) : (200, json(#"[{"Id":"x1"}]"#)) }
        #expect(try await client.listFiles().first?.id == "x1")
        try await client.delete(id: "abc123")
        #expect(MockGokapi.requests.last?.headers["id"] == "abc123")
        #expect(MockGokapi.requests.last?.path == "/sub/api/files/delete")
        MockGokapi.reset { _ in (200, json("<html>login</html>")) }
        await #expect(throws: GokapiError.protocolError("Unexpected response")) { _ = try await client.version() }
    }
}

final class Counter: @unchecked Sendable {
    private var n = 0
    private let lock = NSLock()
    func next() -> Int { lock.withLock { n += 1; return n } }
}

final class Box<T>: @unchecked Sendable {
    private var v: T
    private let lock = NSLock()
    init(_ v: T) { self.v = v }
    var value: T {
        get { lock.withLock { v } }
        set { lock.withLock { v = newValue } }
    }
}

@Suite(.serialized)
@MainActor
struct UploadQueueTests {
    static let remoteFiles = Box(#"[{"Id":"keep"}]"#)

    func makeStores() -> (ServerStore, UploadQueue, URL) {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("panaru-store-\(UUID().uuidString)")
        let servers = ServerStore(directory: dir, secrets: MemorySecretStore(), session: MockGokapi.session())
        return (servers, UploadQueue(servers: servers, directory: dir), dir)
    }

    func gokapiHandler() -> @Sendable (MockGokapi.Recorded) -> (Int, Data) {
        return { r in
            let key = r.headers["apikey"] ?? ""
            guard key == "key-\(r.host.prefix(1))" else { return (401, Data()) }
            if r.path.hasSuffix("info/version") { return (200, json(#"{"Version":"2.2.4","VersionInt":20204}"#)) }
            if r.path.hasSuffix("info/config") { return (200, json(#"{"MaxFilesize":100,"MaxChunksize":5}"#)) }
            if r.path.hasSuffix("chunk/add") { Thread.sleep(forTimeInterval: 0.05); return (200, json(#"{"result":"OK"}"#)) }
            if r.path.hasSuffix("chunk/complete") { return (200, fileInfo("f" + String((r.headers["uuid"] ?? "").prefix(6)))) }
            if r.path.hasSuffix("files/list") { return (200, json(UploadQueueTests.remoteFiles.value)) }
            return (404, Data())
        }
    }

    @Test func serversDefaultAndSecrets() async throws {
        MockGokapi.reset(gokapiHandler())
        let (servers, _, dir) = makeStores()
        let a = try servers.add(name: "A", url: try ServerURL.parse("https://a.example").get(), apiKey: "key-a", makeDefault: false)
        let b = try servers.add(name: "B", url: try ServerURL.parse("https://b.example").get(), apiKey: "key-b", makeDefault: false)
        #expect(servers.defaultServer?.id == a.id)
        servers.setDefault(b.id)
        #expect(servers.defaultServer?.id == b.id)
        let saved = try String(contentsOf: dir.appendingPathComponent("servers.json"), encoding: .utf8)
        #expect(!saved.contains("key-a"), "la chiave non deve finire nel JSON")
        if case .ok = await servers.check(try ServerURL.parse("https://a.example").get(), apiKey: "key-a") {} else {
            Issue.record("check should succeed")
        }
        if case .failed(.unauthorized) = await servers.check(try ServerURL.parse("https://a.example").get(), apiKey: "bad") {} else {
            Issue.record("check should fail with unauthorized")
        }
        servers.remove(b.id)
        #expect(servers.defaultServer?.id == a.id)
    }

    @Test func queueUploadsToRightServersWithConcurrencyLimit() async throws {
        MockGokapi.reset(gokapiHandler())
        let (servers, queue, dir) = makeStores()
        let a = try servers.add(name: "A", url: try ServerURL.parse("https://a.example").get(), apiKey: "key-a", makeDefault: true)
        let b = try servers.add(name: "B", url: try ServerURL.parse("https://b.example").get(), apiKey: "key-b", makeDefault: false)
        queue.maxConcurrent = 2
        let folder = try tempFile(100).deletingLastPathComponent()
        try Data([1, 2, 3]).write(to: folder.appendingPathComponent("second.txt"))
        let idsA = queue.enqueue([folder], serverId: a.id, options: UploadOptions(expiryDays: 3, allowedDownloads: 0, password: "secret"))
        let idsB = queue.enqueue([try tempFile(10)], serverId: b.id, options: UploadOptions())
        #expect(idsA.count == 2, "le cartelle vengono espanse nei file contenuti")
        #expect(queue.active.filter { $0.state == .uploading }.count <= 2)
        await queue.waitUntilIdle()
        for id in idsA + idsB { #expect(queue.records.first { $0.id == id }?.state == .completed) }
        let completes = MockGokapi.requests.filter { $0.path.hasSuffix("chunk/complete") }
        #expect(completes.filter { $0.host == "a.example" }.count == 2)
        #expect(completes.filter { $0.host == "b.example" }.count == 1)
        #expect(completes.filter { $0.headers["password"] == "secret" }.count == 2)
        let history = try String(contentsOf: dir.appendingPathComponent("history.json"), encoding: .utf8)
        #expect(!history.contains("secret"), "le password non vengono salvate")
        #expect(queue.history.allSatisfy { $0.hasUsableLink })
    }

    @Test func cancelRetryAndRefresh() async throws {
        MockGokapi.reset(gokapiHandler())
        let (servers, queue, _) = makeStores()
        let a = try servers.add(name: "A", url: try ServerURL.parse("https://a.example").get(), apiKey: "key-a", makeDefault: true)
        queue.maxConcurrent = 1
        let ids = queue.enqueue([try tempFile(Int(11 * ChunkPlanner.mb)), try tempFile(5)], serverId: a.id, options: UploadOptions())
        queue.cancel(ids[1]) // in coda
        try await Task.sleep(for: .milliseconds(30))
        queue.cancel(ids[0]) // in corso
        await queue.waitUntilIdle()
        #expect(queue.records.first { $0.id == ids[0] }?.state == .cancelled)
        #expect(queue.records.first { $0.id == ids[1] }?.state == .cancelled)
        #expect(queue.retry(ids[1]))
        await queue.waitUntilIdle()
        #expect(queue.records.first { $0.id == ids[1] }?.state == .completed)
        #expect(await queue.sync(serverId: a.id) == .ok)
        #expect(queue.records.first { $0.id == ids[1] }?.removedFromServer == true)
        #expect(queue.records.first { $0.id == ids[1] }?.isExpired == true)
    }

    @Test func syncImportsFilesFromOtherDevices() async throws {
        MockGokapi.reset(gokapiHandler())
        let (servers, queue, dir) = makeStores()
        let a = try servers.add(name: "A", url: try ServerURL.parse("https://a.example").get(), apiKey: "key-a", makeDefault: true)
        let ids = queue.enqueue([try tempFile(3)], serverId: a.id, options: UploadOptions())
        await queue.waitUntilIdle()
        let mine = queue.records.first { $0.id == ids[0] }!.fileId!
        UploadQueueTests.remoteFiles.value = """
        [{"Id":"\(mine)","DownloadsRemaining":7},
         {"Id":"fromAndroid1","Name":"dal-telefono.jpg","SizeBytes":1234,"ContentType":"image/jpeg",
          "UrlDownload":"https://a.example/d?id=fromAndroid1","UploadDate":1700000000,"ExpireAt":4000000000,
          "DownloadsRemaining":2,"IsPasswordProtected":true},
         {"Id":"gone1","Name":"in-cancellazione","IsPendingDeletion":true}]
        """
        defer { UploadQueueTests.remoteFiles.value = #"[{"Id":"keep"}]"# }
        #expect(await queue.sync(serverId: a.id) == .ok)
        #expect(await queue.sync(serverId: a.id) == .ok) // idempotente
        #expect(queue.records.count == 2)
        #expect(queue.records.first { $0.id == ids[0] }?.downloadsRemaining == 7)
        let imported = try #require(queue.records.first { $0.fileId == "fromAndroid1" })
        #expect(imported.fromOtherDevice)
        #expect(imported.state == .completed && imported.hasUsableLink)
        #expect(imported.fileName == "dal-telefono.jpg")
        #expect(imported.mimeType == "image/jpeg")
        #expect(imported.createdAt == Date(timeIntervalSince1970: 1_700_000_000))
        #expect(imported.serverName == "A")

        // Rimossa dalla cronologia: nascosta, non riaggiunta dalla sincronizzazione successiva.
        queue.remove(imported.id)
        #expect(await queue.sync(serverId: a.id) == .ok)
        #expect(queue.records.filter { $0.fileId == "fromAndroid1" }.count == 1)
        #expect(!queue.history.contains { $0.fileId == "fromAndroid1" })

        // La cronologia salvata si ricarica (anche le voci senza il campo "hidden").
        let reloaded = UploadQueue(servers: servers, directory: dir)
        #expect(reloaded.records.count == 2)
    }

    @Test func syncWithoutViewPermission() async throws {
        MockGokapi.reset { _ in (401, Data()) }
        let (servers, queue, _) = makeStores()
        let a = try servers.add(name: "A", url: try ServerURL.parse("https://a.example").get(), apiKey: "key-a", makeDefault: true)
        #expect(await queue.sync(serverId: a.id) == .noPermission)
    }

    @Test func wrongKeyAndMissingFileFail() async throws {
        MockGokapi.reset(gokapiHandler())
        let (servers, queue, dir) = makeStores()
        let a = try servers.add(name: "A", url: try ServerURL.parse("https://a.example").get(), apiKey: "nope", makeDefault: true)
        let ids = queue.enqueue([try tempFile(3)], serverId: a.id, options: UploadOptions(password: "pw"))
        await queue.waitUntilIdle()
        let r = queue.records.first { $0.id == ids[0] }!
        #expect(r.state == .failed)
        #expect(r.errorMessage == GokapiError.unauthorized.errorDescription)
        // Nuova istanza (riavvio): la password non c'è più, il retry viene rifiutato.
        let restarted = UploadQueue(servers: servers, directory: dir)
        #expect(restarted.records.contains { $0.id == ids[0] })
        #expect(!restarted.retry(ids[0]))
    }
}

@Suite(.serialized)
struct KeychainTests {
    @Test func roundTrip() throws {
        let store = KeychainStore(service: "app.panaru.tests.\(UUID().uuidString)")
        defer { store.delete(account: "acc") }
        try store.set("s3cret", account: "acc")
        #expect(try store.get(account: "acc") == "s3cret")
        try store.set("changed", account: "acc")
        #expect(try store.get(account: "acc") == "changed")
        store.delete(account: "acc")
        #expect(try store.get(account: "acc") == nil)
    }
}
