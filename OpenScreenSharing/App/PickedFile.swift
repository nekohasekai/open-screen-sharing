import Foundation

extension URL {
    nonisolated func withPickedFile<T>(options: NSFileCoordinator.ReadingOptions = [], _ body: (URL) throws -> T) throws -> T {
        let isScoped = startAccessingSecurityScopedResource()
        defer {
            if isScoped {
                stopAccessingSecurityScopedResource()
            }
        }
        var coordinationError: NSError?
        var result: Result<T, any Error>?
        NSFileCoordinator().coordinate(readingItemAt: self, options: options, error: &coordinationError) { url in
            result = Result { try body(url) }
        }
        if let coordinationError {
            throw coordinationError
        }
        guard let result else {
            throw MessageError("Couldn’t read \(lastPathComponent)")
        }
        return try result.get()
    }
}
