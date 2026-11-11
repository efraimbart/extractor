/// yt-dlp search backends and URL/option builders for flat playlist search.
///
/// Search prefixes follow SearchInfoExtractor conventions:
/// `ytsearchN:query`, `ytsearchdateN:query`, `scsearchN:query`, …

/// Practical search backends for a mobile video app.
enum SearchBackend {
  /// YouTube relevance ranking (`ytsearch`).
  youtube('ytsearch', 'YouTube'),

  /// YouTube newest-first (`ytsearchdate`).
  ///
  /// Dead on yt-dlp ≥ ~2026.02: [YoutubeSearchDateIE was removed](https://github.com/yt-dlp/yt-dlp/pull/15959)
  /// after YouTube dropped the date filter. Requests become
  /// `Unsupported url scheme: "ytsearchdateN"`. Kept on the enum for API
  /// compatibility; do **not** put in [kDefaultSearchBackends].
  youtubeDate('ytsearchdate', 'YouTube · Recent'),

  /// SoundCloud tracks (`scsearch`) — optional audio-leaning source.
  soundcloud('scsearch', 'SoundCloud');

  const SearchBackend(this.searchKey, this.label);

  final String searchKey;
  final String label;
}

/// One page of a backend search.
class SearchRequest {
  const SearchRequest({
    required this.query,
    required this.backend,
    this.offset = 0,
    this.limit = 12,
    this.processId,
  });

  final String query;
  final SearchBackend backend;
  final int offset;
  final int limit;

  /// Optional id for [YoutubeDL.destroyProcessById] / cancelDownload.
  final String? processId;

  /// Total results yt-dlp must expand so [playlistItems] can slice this page.
  int get totalNeeded => offset + limit;

  /// yt-dlp search URL, e.g. `ytsearch24:cats`.
  String get url {
    final q = query.trim();
    return '${backend.searchKey}$totalNeeded:$q';
  }

  /// 1-based inclusive range for this page.
  String get playlistItems {
    final start = offset + 1;
    final end = offset + limit;
    return '$start:$end';
  }

  /// Flat / skip-download options suitable for a results grid.
  Map<String, String> get options {
    final opts = <String, String>{
      '--flat-playlist': '',
      '--skip-download': '',
      '--playlist-items': playlistItems,
      '--playlist-end': '$totalNeeded',
    };
    final id = processId;
    if (id != null && id.isNotEmpty) {
      opts['__process_id'] = id;
    }
    return opts;
  }
}

/// Default backends for fan-out search (YouTube relevance only).
///
/// `youtubeDate` is omitted: yt-dlp removed `ytsearchdate` upstream (2026-02).
const List<SearchBackend> kDefaultSearchBackends = [
  SearchBackend.youtube,
];
