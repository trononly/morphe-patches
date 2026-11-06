package app.spicetify.extension.spotify.extensions;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The esperanto messages the extensions send and receive over {@code sp://esperanto/<service>/
 * <method>}: request builders, response parsers and the player state value. The field numbers
 * were read from Spotify 9.1.80.2221's protobuf classes, and the extensions patch checks them
 * again against the APK it patches.
 */
final class Esperanto {
    static final String CONTEXT_PLAYER = "spotify.player.esperanto.proto.ContextPlayer";
    static final String PLAYLIST = "spotify.playlist_esperanto.proto.PlaylistDataService";
    static final String METADATA = "spotify.metadata_esperanto.proto.ClassicMetadataService";
    /** With an underscore in {@code your_library_esperanto}; the dotted name has no route. */
    static final String YOUR_LIBRARY = "spotify.your_library_esperanto.proto.YourLibraryService";
    static final String LIKED_SONGS = "spotify:playlist:37i9dQZF1F5p3rmiWPIYgZ";

    static final int OK = 0;

    static final int FORBIDDEN = 1;

    private static final String BASE62_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int FILTER_ALBUM = 0;
    private static final int FILTER_PLAYLIST = 2;
    private static final int ENTITY_ALBUM = 2;
    private static final int ENTITY_PLAYLIST = 4;
    private static final int LINK_TYPE_TRACK = 4;

    private Esperanto() {}

    static final class PlayerState {

        String contextUri;
        String trackUri;
        String trackUid;
        List<String> artistUris = new ArrayList<>();

        List<String> nextTrackUris = new ArrayList<>();

        Map<String, String> metadata = new TreeMap<>();

        long positionMs = -1;
        long trackDurationMs = -1;

        int playbackState = -1;
        boolean advertisement;
        boolean episode;

        boolean isPlaying() {
            return playbackState == 3 || playbackState == 4 || playbackState == 5
                    || playbackState == 6;
        }

        String title() {
            return metadata.get("title");
        }

        String artist() {
            return metadata.get("artist_name");
        }

        String imageUrl() {
            String url = metadata.get("image_url");
            if (url == null) url = metadata.get("image_xlarge_url");
            return url;
        }

        long durationMs() {
            String value = metadata.get("duration");
            if (value == null) return -1;
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        long currentPositionMs() {
            if (positionMs >= 0) return positionMs;
            String value = metadata.get("media.start_position");
            if (value == null) return -1;
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    static PlayerState parseState(byte[] contextPlayerState) throws IOException {
        PlayerState state = new PlayerState();
        Wire.Reader reader = new Wire.Reader(contextPlayerState);
        while (reader.next()) {
            switch (reader.field()) {
                case 2:
                    state.contextUri = reader.string();
                    break;
                case 7:
                    readProvidedTrack(reader.message(), state);
                    break;
                case 9:
                    readPlaybackState(reader.message(), state);
                    break;
                case 10:
                    state.positionMs = readTime(reader.message());
                    break;
                case 11:
                    state.trackDurationMs = readTime(reader.message());
                    break;
                case 21:
                    String nextUri = readProvidedTrackUri(reader.message());
                    if (nextUri != null) state.nextTrackUris.add(nextUri);
                    break;
                default:
                    reader.skip();
            }
        }
        return state;
    }

    private static String readProvidedTrackUri(Wire.Reader providedTrack) throws IOException {
        String uri = null;
        while (providedTrack.next()) {
            if (providedTrack.field() == 1) {
                Wire.Reader contextTrack = providedTrack.message();
                while (contextTrack.next()) {
                    if (contextTrack.field() == 1 && uri == null) {
                        uri = contextTrack.string();
                    } else {
                        contextTrack.skip();
                    }
                }
            } else {
                providedTrack.skip();
            }
        }
        return uri;
    }

    private static void readPlaybackState(Wire.Reader playback, PlayerState state) throws IOException {
        while (playback.next()) {
            if (playback.field() == 1) {
                state.playbackState = (int) playback.varint();
            } else {
                playback.skip();
            }
        }
    }

    private static long readTime(Wire.Reader time) throws IOException {
        long value = -1;
        while (time.next()) {
            if (time.field() == 1) {
                value = time.varint();
            } else {
                time.skip();
            }
        }
        return value;
    }

    private static void readProvidedTrack(Wire.Reader providedTrack, PlayerState state) throws IOException {        while (providedTrack.next()) {
            if (providedTrack.field() == 1) {
                readContextTrack(providedTrack.message(), state);
            } else {
                providedTrack.skip();
            }
        }
    }

    private static void readContextTrack(Wire.Reader contextTrack, PlayerState state) throws IOException {
        Map<String, String> metadata = new TreeMap<>();
        while (contextTrack.next()) {
            switch (contextTrack.field()) {
                case 1:
                    state.trackUri = contextTrack.string();
                    break;
                case 2:
                    state.trackUid = contextTrack.string();
                    break;
                case 3:
                    readMetadataEntry(contextTrack.message(), metadata);
                    break;
                default:
                    contextTrack.skip();
            }
        }
        // artist_uri, artist_uri:1, artist_uri:2 and so on, in that order.
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            if (entry.getKey().startsWith("artist_uri") && !entry.getValue().isEmpty()) {
                state.artistUris.add(entry.getValue());
            }
        }
        state.advertisement = "true".equals(metadata.get("is_advertisement"));
        state.episode = isEpisodeUri(state.trackUri);
        state.metadata = metadata;
    }

    private static void readMetadataEntry(Wire.Reader entry, Map<String, String> metadata) throws IOException {
        String key = null;
        String value = "";
        while (entry.next()) {
            switch (entry.field()) {
                case 1:
                    key = entry.string();
                    break;
                case 2:
                    value = entry.string();
                    break;
                default:
                    entry.skip();
            }
        }
        if (key != null) {
            metadata.put(key, value);
        }
    }

    private static boolean isEpisodeUri(String uri) {
        return uri != null
                && (uri.startsWith("spotify:episode:")
                        || uri.startsWith("spotify:podcast-chapter:")
                        || uri.startsWith("spotify:clip:"));
    }


    static byte[] skipNext() {
        return new byte[0];
    }

    static byte[] getState() {
        Wire.Writer none = new Wire.Writer();
        none.varint(1, 0);
        Wire.Writer one = new Wire.Writer();
        one.varint(1, 1);
        Wire.Writer request = new Wire.Writer();
        request.message(1, none);
        request.message(2, one);
        return request.toByteArray();
    }

    static byte[] playContext(String contextUri, String skipToTrackUri) {
        Wire.Writer context = new Wire.Writer();
        context.string(3, contextUri);
        context.string(4, "context://" + contextUri);

        Wire.Writer prepare = new Wire.Writer();
        prepare.message(1, context);
        if (skipToTrackUri != null) {
            Wire.Writer skipTo = new Wire.Writer();
            skipTo.string(4, skipToTrackUri);
            Wire.Writer options = new Wire.Writer();
            options.message(3, skipTo);
            prepare.message(2, options);
        }
        Wire.Writer request = new Wire.Writer();
        request.message(1, prepare);
        return request.toByteArray();
    }

    /**
     * {@code PlayRequest{1 prepare_play_request{1 context{1 pages{1 tracks*{1 uri}}, 3 uri}, 2 options{3
     * skip_to{5 track_index{1 0}}, 7 player_options_override{1 shuffling_context{1 false}}}}}}: plays
     * {@code trackUris} in exactly that order, as one explicit page of {@code contextUri}, from the
     * first, with Spotify's own shuffle off.
     */
    static byte[] playOrder(String contextUri, List<String> trackUris) {
        Wire.Writer page = new Wire.Writer();
        for (String trackUri : trackUris) {
            Wire.Writer track = new Wire.Writer();
            track.string(1, trackUri);
            page.message(1, track);
        }
        // No url: with one, the core could load the context again and drop this page.
        Wire.Writer context = new Wire.Writer();
        context.message(1, page);
        context.string(3, contextUri);

        Wire.Writer trackIndex = new Wire.Writer();
        trackIndex.varint(1, 0);
        Wire.Writer skipTo = new Wire.Writer();
        skipTo.message(5, trackIndex);
        Wire.Writer shufflingContext = new Wire.Writer();
        shufflingContext.bool(1, false);
        Wire.Writer overrides = new Wire.Writer();
        overrides.message(1, shufflingContext);
        Wire.Writer options = new Wire.Writer();
        options.message(3, skipTo);
        options.message(7, overrides);

        Wire.Writer prepare = new Wire.Writer();
        prepare.message(1, context);
        prepare.message(2, options);
        Wire.Writer request = new Wire.Writer();
        request.message(1, prepare);
        return request.toByteArray();
    }

    static byte[] playlistGet(String uri, int start, int length) {
        Wire.Writer range = new Wire.Writer();
        range.varint(1, start);
        range.varint(2, length);
        Wire.Writer query = new Wire.Writer();
        query.bytes(1, packedVarints(4, 3, 7, 6)); // NOT_BANNED, ARTIST_NOT_BANNED, NOT_RECOMMENDATION, NOT_EPISODE
        query.bool(8, false);
        query.message(4, range);

        Wire.Writer playlist = new Wire.Writer();
        playlist.bool(49, true);
        Wire.Writer item = new Wire.Writer();
        item.bool(1, true);
        Wire.Writer policy = new Wire.Writer();
        policy.message(1, playlist);
        policy.message(4, item);

        Wire.Writer request = new Wire.Writer();
        request.string(1, uri);
        request.message(2, query);
        request.message(3, policy);
        return request.toByteArray();
    }

    private static byte[] packedVarints(int... values) {
        Wire.Writer packed = new Wire.Writer();
        for (int value : values) {
            packed.rawVarint(value);
        }
        return packed.toByteArray();
    }

    static byte[] getEntity(String uri) {
        Wire.Writer request = new Wire.Writer();
        request.string(1, uri);
        return request.toByteArray();
    }

    // Entity    1 entity_info{2 name, 3 uri}; case 2 album, 3 artist, 4 playlist (Liked Songs too), 6 folder

    /**
     * Every playlist, with folders flattened, and every saved album, in one page: {@code header{12
     * length 0x7fffffff, 14 filters[PLAYLIST, ALBUM], 17 all_playlists, 25 num_link_types_in_playlists,
     * 26 ignore_pinning}}. A length of 0 would be an empty page. It asks for no predefined playlists,
     * so the Library's own Liked Songs row stays out.
     */
    static byte[] yourLibraryAll() {
        Wire.Writer filters = new Wire.Writer();
        filters.bytes(1, packedVarints(FILTER_PLAYLIST, FILTER_ALBUM));
        Wire.Writer header = new Wire.Writer();
        header.varint(12, Integer.MAX_VALUE);
        header.message(14, filters);
        header.bool(17, true);
        header.bool(25, true);
        header.bool(26, true);
        Wire.Writer request = new Wire.Writer();
        request.message(1, header);
        return request.toByteArray();
    }

    /** The four uris the app treats as Liked Songs ({@code Lp/x46;->E} in 9.1.80.2221). */
    static boolean isLikedSongs(String uri) {
        return LIKED_SONGS.equals(uri)
                || "spotify:collection:tracks".equals(uri)
                || "spotify:internal:collection:tracks".equals(uri)
                || uri.startsWith("spotify:user:") && uri.endsWith(":collection");
    }


    static int parseResult(byte[] responseWithReasons) throws IOException {
        Wire.Reader reader = new Wire.Reader(responseWithReasons);
        int error = 0;
        while (reader.next()) {
            if (reader.field() == 1) {
                error = (int) reader.varint();
            } else {
                reader.skip();
            }
        }
        return error;
    }

    /**
     * A page of a playlist or Liked Songs: its playable {@code length}, the uris read, and whether the
     * core is still loading the list, which makes the page incomplete.
     */
    static final class PlaylistPage {
        int length;
        List<String> uris = new ArrayList<>();
        boolean loading;
    }

    static PlaylistPage parsePlaylistGet(byte[] playlistGetResponse) throws IOException {
        PlaylistPage page = new PlaylistPage();
        Wire.Reader response = new Wire.Reader(playlistGetResponse);
        int statusCode = 0;
        Wire.Reader data = null;
        while (response.next()) {
            switch (response.field()) {
                case 1:
                    statusCode = readStatusCode(response.message());
                    break;
                case 2:
                    data = response.message();
                    break;
                default:
                    response.skip();
            }
        }
        if (statusCode == 403 || statusCode == 404 || statusCode == 451) {
            return page;
        }
        if (statusCode < 200 || statusCode > 299) {
            throw new IOException("status " + statusCode);
        }
        if (data != null) {
            readPlaylistData(data, page);
        }
        return page;
    }

    private static int readStatusCode(Wire.Reader status) throws IOException {
        int statusCode = 0;
        while (status.next()) {
            if (status.field() == 1) {
                statusCode = (int) status.varint();
            } else {
                status.skip();
            }
        }
        return statusCode;
    }

    private static void readPlaylistData(Wire.Reader data, PlaylistPage page) throws IOException {
        while (data.next()) {
            switch (data.field()) {
                case 1:
                    readPlaylistItem(data.message(), page);
                    break;
                case 4:
                    page.length = (int) data.varint();
                    break;
                case 6:
                    page.loading = data.varint() != 0;
                    break;
                default:
                    data.skip();
            }
        }
    }

    private static void readPlaylistItem(Wire.Reader item, PlaylistPage page) throws IOException {
        while (item.next()) {
            if (item.field() == 18) {
                page.uris.add(item.string());
            } else {
                item.skip();
            }
        }
    }

    /** Where a random song from the library can come from: Liked Songs, a playlist or a saved album. */
    static final class LibrarySource {
        String uri;

        String name;
        String image;
        boolean album;
        /** A playlist's song count from Your Library, or -1 without one, as for albums and Liked Songs. */
        int trackCount = -1;
    }

    /** A {@code YourLibraryResponse}: whether it's still loading, and Liked Songs, then each playlist and album once. */
    static final class Library {
        boolean loading;
        List<LibrarySource> sources = new ArrayList<>();
    }

    /**
     * Reads a {@link #yourLibraryAll()} answer. {@code entity} and {@code pinned_entity} merge by uri,
     * first one wins, keeping albums and playlists; folders are dropped, since {@code all_playlists}
     * lists their playlists. Liked Songs comes first, once, under {@link #LIKED_SONGS}. A status other
     * than 200 throws with the core's error.
     */
    static Library parseYourLibrary(byte[] yourLibraryResponse) throws IOException {
        Library library = new Library();
        Map<String, LibrarySource> found = new LinkedHashMap<>();
        int statusCode = 0;
        String error = "";
        Wire.Reader response = new Wire.Reader(yourLibraryResponse);
        while (response.next()) {
            switch (response.field()) {
                case 1:
                    library.loading = readIsLoading(response.message());
                    break;
                case 2:
                case 3:
                    readLibraryEntity(response.message(), found);
                    break;
                case 98:
                    statusCode = (int) response.varint();
                    break;
                case 99:
                    error = response.string();
                    break;
                default:
                    response.skip();
            }
        }
        if (statusCode != 200) throw new IOException("status " + statusCode + (error.isEmpty() ? "" : ": " + error));
        LibrarySource likedSongs = new LibrarySource();
        likedSongs.uri = LIKED_SONGS;
        library.sources.add(likedSongs);
        library.sources.addAll(found.values());
        return library;
    }

    private static boolean readIsLoading(Wire.Reader header) throws IOException {
        boolean loading = false;
        while (header.next()) {
            if (header.field() == 12) {
                loading = header.varint() != 0;
            } else {
                header.skip();
            }
        }
        return loading;
    }

    private static void readLibraryEntity(Wire.Reader entity, Map<String, LibrarySource> found) throws IOException {
        LibrarySource source = new LibrarySource();
        int kind = 0;
        while (entity.next()) {
            switch (entity.field()) {
                case 1:
                    readEntityInfo(entity.message(), source);
                    break;
                case ENTITY_ALBUM:
                    kind = ENTITY_ALBUM;
                    entity.skip();
                    break;
                case ENTITY_PLAYLIST:
                    kind = ENTITY_PLAYLIST;
                    source.trackCount = readTrackCount(entity.message());
                    break;
                default:
                    entity.skip();
            }
        }
        if (kind == 0 || source.uri == null || source.uri.isEmpty() || isLikedSongs(source.uri)) return;
        source.album = kind == ENTITY_ALBUM;
        found.putIfAbsent(source.uri, source);
    }

    private static void readEntityInfo(Wire.Reader entityInfo, LibrarySource source) throws IOException {
        while (entityInfo.next()) {
            switch (entityInfo.field()) {
                case 2:
                    source.name = entityInfo.string();
                    break;
                case 3:
                    source.uri = entityInfo.string();
                    break;
                case 6:
                    source.image = entityInfo.string();
                    break;
                default:
                    entityInfo.skip();
            }
        }
    }

    private static int readTrackCount(Wire.Reader playlist) throws IOException {
        int tracks = -1;
        while (playlist.next()) {
            if (playlist.field() != 12) {
                playlist.skip();
                continue;
            }
            Wire.Reader count = playlist.message();
            long linkType = 0;
            long items = 0;
            while (count.next()) {
                if (count.field() == 1) {
                    linkType = count.varint();
                } else if (count.field() == 2) {
                    items = count.varint();
                } else {
                    count.skip();
                }
            }
            if (linkType == LINK_TYPE_TRACK) tracks = (int) items;
        }
        return tracks;
    }

    static List<String> parseAlbumTracks(byte[] getEntityResponse) throws IOException {
        List<String> uris = new ArrayList<>();
        Wire.Reader response = new Wire.Reader(getEntityResponse);
        while (response.next()) {
            if (response.field() == 1) {
                readMetadataItem(response.message(), uris);
            } else {
                response.skip();
            }
        }
        return uris;
    }

    private static void readMetadataItem(Wire.Reader item, List<String> uris) throws IOException {
        while (item.next()) {
            if (item.field() == 3) { // oneof case 3: the album
                readAlbum(item.message(), uris);
            } else {
                item.skip();
            }
        }
    }

    private static void readAlbum(Wire.Reader album, List<String> uris) throws IOException {
        while (album.next()) {
            if (album.field() == 11) {
                readDisc(album.message(), uris);
            } else {
                album.skip();
            }
        }
    }

    private static void readDisc(Wire.Reader disc, List<String> uris) throws IOException {
        while (disc.next()) {
            if (disc.field() == 3) {
                readTrack(disc.message(), uris);
            } else {
                disc.skip();
            }
        }
    }

    private static void readTrack(Wire.Reader track, List<String> uris) throws IOException {
        while (track.next()) {
            if (track.field() == 1) {
                uris.add("spotify:track:" + base62(track.bytes()));
            } else {
                track.skip();
            }
        }
    }

    static String base62(byte[] gid) {
        BigInteger value = new BigInteger(1, gid);
        BigInteger base = BigInteger.valueOf(62);
        StringBuilder encoded = new StringBuilder();
        while (value.signum() > 0) {
            BigInteger[] divRem = value.divideAndRemainder(base);
            encoded.append(BASE62_ALPHABET.charAt(divRem[1].intValue()));
            value = divRem[0];
        }
        while (encoded.length() < 22) {
            encoded.append('0');
        }
        return encoded.reverse().toString();
    }
}
