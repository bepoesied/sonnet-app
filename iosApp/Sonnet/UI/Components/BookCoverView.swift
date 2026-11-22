import SwiftUI
import UIKit

/// Rounded audiobook cover with local-first loading, stable `bookId` caching, and monogram fallback.
struct BookCoverView: View {
    let bookId: String?
    let title: String
    let localCoverPath: String?
    var remoteCoverUrl: String? = nil
    var cornerRadius: CGFloat = 10
    var monogramFont: Font = .title2.weight(.semibold)
    var crossfade: Bool = true

    @State private var loadedImage: UIImage?

    private var taskKey: String {
        "\(bookId ?? "")|\(localCoverPath ?? "")|\(remoteCoverUrl ?? "")"
    }

    private var monogram: String {
        guard let first = title.trimmingCharacters(in: .whitespacesAndNewlines).first else {
            return "?"
        }
        return String(first).uppercased()
    }

    private var accessibilityTitle: String {
        title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? "Book cover"
            : "\(title) cover"
    }

    var body: some View {
        ZStack {
            Color(uiColor: .secondarySystemFill)

            if let image = loadedImage ?? CoverImageLoader.shared.cachedImage(
                bookId: bookId,
                localCoverPath: localCoverPath
            ) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .transition(crossfade ? .opacity : .identity)
            } else {
                Text(monogram)
                    .font(monogramFont)
                    .foregroundStyle(.secondary)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityTitle)
        .task(id: taskKey) {
            let image = await CoverImageLoader.shared.loadImage(
                bookId: bookId,
                localCoverPath: localCoverPath,
                remoteCoverUrl: remoteCoverUrl
            )
            if crossfade {
                withAnimation(.easeInOut(duration: 0.2)) {
                    loadedImage = image
                }
            } else {
                loadedImage = image
            }
        }
    }
}
