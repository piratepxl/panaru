import Foundation

/// Client per l'API di Gokapi. Tutti i metodi sono `async` e annullabili tramite la cancellazione
/// del `Task`. Il comportamento è verificato su `openapi.json` e sui sorgenti (docs/GOKAPI_API.md).
public final class GokapiClient: @unchecked Sendable {
    public typealias Progress = @Sendable (_ sent: Int64, _ total: Int64) -> Void

    public struct RetryPolicy: Sendable {
        public var maxAttempts: Int
        public var initialDelay: Duration
        public init(maxAttempts: Int = 3, initialDelay: Duration = .seconds(1)) {
            self.maxAttempts = maxAttempts
            self.initialDelay = initialDelay
        }
    }

    private let api: URL
    private let apiKey: String
    private let session: URLSession
    private let retry: RetryPolicy

    /// - Parameter baseURL: indirizzo del server (es. `https://host/` o `https://host/gokapi/`).
    public init(baseURL: URL, apiKey: String, session: URLSession = GokapiClient.defaultSession, retry: RetryPolicy = .init()) {
        var base = baseURL.absoluteString
        if !base.hasSuffix("/") { base += "/" }
        self.api = URL(string: base + "api/")!
        self.apiKey = apiKey
        self.session = session
        self.retry = retry
    }

    /// Sessione effimera: nessuna cache su disco, nessun cookie, timeout adatti a upload lunghi.
    public static let defaultSession: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 120
        c.timeoutIntervalForResource = 24 * 3600
        c.httpCookieStorage = nil
        c.urlCache = nil
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()

    // MARK: info

    public func version() async throws -> VersionInfo { try await getJSON("info/version") }
    public func config() async throws -> ServerConfig { try await getJSON("info/config") }

    // MARK: files

    public func listFiles() async throws -> [GokapiFile] {
        let data = try await send(request("files/list"))
        if data.isEmpty || String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespaces) == "null" { return [] }
        return try decode(data)
    }

    public func file(id: String) async throws -> GokapiFile { try await getJSON("files/list/\(try safeId(id))") }

    public func delete(id: String) async throws {
        var r = request("files/delete")
        r.httpMethod = "DELETE"
        r.setValue(id, forHTTPHeaderField: "id")
        _ = try await send(r)
    }

    /// Scarica un file via API (permesso DOWNLOAD) in `destination`, senza incrementare il contatore.
    public func download(id: String, to destination: URL, progress: Progress? = nil) async throws {
        let req = request("files/download/\(try safeId(id))")
        let delegate = TaskDelegate(download: progress)
        let (tmp, response): (URL, URLResponse)
        do {
            (tmp, response) = try await session.download(for: req, delegate: delegate)
        } catch {
            throw map(error)
        }
        defer { try? FileManager.default.removeItem(at: tmp) }
        try check(response, body: (try? Data(contentsOf: tmp, options: .mappedIfSafe)) ?? Data())
        let fm = FileManager.default
        if fm.fileExists(atPath: destination.path) { try fm.removeItem(at: destination) }
        try fm.moveItem(at: tmp, to: destination)
    }

    // MARK: upload

    /// Carica `fileURL` a chunk (`/chunk/add` + `/chunk/complete`) e restituisce il file creato.
    /// Ogni chunk viene scritto in un file temporaneo multipart e inviato in streaming:
    /// in memoria resta solo un buffer di copia da 1 MB.
    public func upload(
        fileURL: URL,
        fileName: String? = nil,
        mimeType: String? = nil,
        options: UploadOptions,
        config: ServerConfig?,
        progress: Progress? = nil
    ) async throws -> GokapiFile {
        let size: Int64
        let handle: FileHandle
        do {
            let attrs = try FileManager.default.attributesOfItem(atPath: fileURL.path)
            size = (attrs[.size] as? NSNumber)?.int64Value ?? 0
            handle = try FileHandle(forReadingFrom: fileURL)
        } catch {
            throw GokapiError.sourceUnavailable(error.localizedDescription)
        }
        defer { try? handle.close() }
        if ChunkPlanner.exceedsLimit(size, config) { throw GokapiError.fileTooLarge(maxMB: config?.maxFileSizeMB) }

        let uuid = UUID().uuidString
        var confirmed: Int64 = 0
        progress?(0, size)
        for chunk in ChunkPlanner.plan(size: size, chunkSize: ChunkPlanner.chunkSize(config)) {
            try Task.checkCancellation()
            let body = try MultipartChunk.write(handle: handle, chunk: chunk, uuid: uuid, total: size)
            defer { try? FileManager.default.removeItem(at: body.file) }
            let base = confirmed
            try await retrying {
                var req = self.request("chunk/add")
                req.httpMethod = "POST"
                req.setValue("multipart/form-data; boundary=\(body.boundary)", forHTTPHeaderField: "Content-Type")
                let delegate = TaskDelegate(upload: { sent, _ in
                    progress?(base + min(sent - body.headerLength, chunk.length).clamped(0), size)
                })
                do {
                    let (data, response) = try await self.session.upload(for: req, fromFile: body.file, delegate: delegate)
                    try self.check(response, body: data)
                } catch let e as GokapiError {
                    throw e
                } catch {
                    throw self.map(error)
                }
            }
            confirmed += chunk.length
            progress?(confirmed, size)
        }

        var complete = request("chunk/complete", timeout: 15 * 60)
        complete.httpMethod = "POST"
        complete.setValue(uuid, forHTTPHeaderField: "uuid")
        complete.setValue(ChunkPlanner.encodeFileName(fileName ?? fileURL.lastPathComponent), forHTTPHeaderField: "filename")
        complete.setValue(String(size), forHTTPHeaderField: "filesize")
        complete.setValue(mimeType ?? "application/octet-stream", forHTTPHeaderField: "contenttype")
        complete.setValue(String(options.allowedDownloads), forHTTPHeaderField: "allowedDownloads")
        complete.setValue(String(options.expiryDays), forHTTPHeaderField: "expiryDays")
        if let pw = options.password, !pw.isEmpty { complete.setValue(pw, forHTTPHeaderField: "password") }
        complete.httpBody = Data()
        let result: UploadResult = try decode(try await send(complete))
        guard let file = result.fileInfo else { throw GokapiError.protocolError("Missing FileInfo") }
        return file
    }

    // MARK: internals

    private func retrying(_ block: () async throws -> Void) async throws {
        var attempt = 1
        var wait = retry.initialDelay
        while true {
            do {
                return try await block()
            } catch let e as GokapiError where e.isRetryable && attempt < retry.maxAttempts {
                try await Task.sleep(for: wait)
                wait *= 2
                attempt += 1
            }
        }
    }

    private func request(_ path: String, timeout: TimeInterval = 120) -> URLRequest {
        var r = URLRequest(url: api.appendingPathComponent(path, isDirectory: false), timeoutInterval: timeout)
        r.setValue(apiKey, forHTTPHeaderField: "apikey")
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        r.cachePolicy = .reloadIgnoringLocalCacheData
        return r
    }

    private func getJSON<T: Decodable>(_ path: String) async throws -> T { try decode(try await send(request(path))) }

    private func send(_ req: URLRequest) async throws -> Data {
        do {
            let (data, response) = try await session.data(for: req, delegate: TaskDelegate())
            try check(response, body: data)
            return data
        } catch let e as GokapiError {
            throw e
        } catch {
            throw map(error)
        }
    }

    private func check(_ response: URLResponse, body: Data) throws {
        guard let http = response as? HTTPURLResponse else { throw GokapiError.protocolError("Not HTTP") }
        guard !(200..<300).contains(http.statusCode) else { return }
        let err = try? JSONDecoder().decode(ErrorBody.self, from: body)
        switch http.statusCode {
        case 401: throw GokapiError.unauthorized
        case 404: throw GokapiError.notFound
        case 429: throw GokapiError.rateLimited
        default:
            if err?.code == 9 { throw GokapiError.fileTooLarge(maxMB: nil) }
            let msg = (err?.message?.isEmpty == false ? err?.message : nil) ?? "HTTP \(http.statusCode)"
            throw GokapiError.server(status: http.statusCode, code: err?.code, message: msg)
        }
    }

    private func decode<T: Decodable>(_ data: Data) throws -> T {
        do { return try JSONDecoder().decode(T.self, from: data) } catch {
            throw GokapiError.protocolError("Unexpected response")
        }
    }

    private func map(_ error: Error) -> GokapiError {
        if error is CancellationError { return .cancelled }
        if let e = error as? GokapiError { return e }
        guard let u = error as? URLError else { return .network(error.localizedDescription) }
        switch u.code {
        case .cancelled: return .cancelled
        case .serverCertificateUntrusted, .serverCertificateHasBadDate, .serverCertificateNotYetValid,
             .serverCertificateHasUnknownRoot, .secureConnectionFailed, .clientCertificateRejected,
             .clientCertificateRequired, .appTransportSecurityRequiresSecureConnection:
            return .tls(u.localizedDescription)
        case .fileDoesNotExist, .noPermissionsToReadFile, .cannotOpenFile:
            return .sourceUnavailable(u.localizedDescription)
        default: return .network(u.localizedDescription)
        }
    }

    private func safeId(_ id: String) throws -> String {
        guard !id.isEmpty, id.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }) else {
            throw GokapiError.protocolError("Invalid file id")
        }
        return id
    }
}

/// Delegate per singola richiesta: avanzamento e **blocco dei redirect**
/// (un redirect inoltrerebbe l'header `apikey` a un altro host).
private final class TaskDelegate: NSObject, URLSessionTaskDelegate, URLSessionDownloadDelegate, @unchecked Sendable {
    private let upload: GokapiClient.Progress?
    private let downloadProgress: GokapiClient.Progress?

    init(upload: GokapiClient.Progress? = nil, download: GokapiClient.Progress? = nil) {
        self.upload = upload
        self.downloadProgress = download
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest) async -> URLRequest? { nil }

    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64,
                    totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        upload?(totalBytesSent, totalBytesExpectedToSend)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        downloadProgress?(totalBytesWritten, totalBytesExpectedToWrite)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {}
}

/// Corpo multipart di un chunk scritto su file temporaneo (streaming, memoria costante).
enum MultipartChunk {
    struct Body {
        let file: URL
        let boundary: String
        let headerLength: Int64
    }

    static func write(handle: FileHandle, chunk: ChunkPlanner.Chunk, uuid: String, total: Int64) throws -> Body {
        let boundary = "PanaruBoundary\(UUID().uuidString.replacingOccurrences(of: "-", with: ""))"
        var header = ""
        for (name, value) in [("uuid", uuid), ("filesize", String(total)), ("offset", String(chunk.offset))] {
            header += "--\(boundary)\r\nContent-Disposition: form-data; name=\"\(name)\"\r\n\r\n\(value)\r\n"
        }
        header += "--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"chunk\"\r\n"
        header += "Content-Type: application/octet-stream\r\n\r\n"
        let footer = "\r\n--\(boundary)--\r\n"

        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("Panaru-chunks", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(UUID().uuidString)
        FileManager.default.createFile(atPath: url.path, contents: nil, attributes: [.posixPermissions: 0o600])
        let out = try FileHandle(forWritingTo: url)
        do {
            defer { try? out.close() }
            let headerData = Data(header.utf8)
            try out.write(contentsOf: headerData)
            do {
                try handle.seek(toOffset: UInt64(chunk.offset))
                var remaining = chunk.length
                while remaining > 0 {
                    let n = Int(min(remaining, 1 << 20))
                    guard let data = try handle.read(upToCount: n), !data.isEmpty else {
                        throw GokapiError.sourceUnavailable("File changed while uploading")
                    }
                    try out.write(contentsOf: data)
                    remaining -= Int64(data.count)
                }
            } catch let e as GokapiError {
                throw e
            } catch {
                throw GokapiError.sourceUnavailable(error.localizedDescription)
            }
            try out.write(contentsOf: Data(footer.utf8))
            return Body(file: url, boundary: boundary, headerLength: Int64(headerData.count))
        } catch {
            try? FileManager.default.removeItem(at: url)
            throw error
        }
    }
}

extension Int64 {
    func clamped(_ low: Int64) -> Int64 { Swift.max(self, low) }
}
