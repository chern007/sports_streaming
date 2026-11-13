package com.rea.directos;

import java.util.Objects;

import androidx.media3.cast.MediaItemConverter;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;

import com.google.android.gms.cast.HlsVideoSegmentFormat;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaQueueItem;

/** Carga del directo y conversión del estado del receptor para los controles de Media3. */
final class LiveCastMedia implements MediaItemConverter {

    static MediaLoadRequestData loadRequest(String url, String title) {
        return new MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo(url, title))
                .setAutoplay(true)
                .build();
    }

    private static MediaInfo mediaInfo(String url, String title) {
        MediaMetadata metadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC);
        if (title != null) metadata.putString(MediaMetadata.KEY_TITLE, title);
        return new MediaInfo.Builder(url)
                .setContentUrl(url)
                .setContentType(MimeTypes.APPLICATION_M3U8)
                .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
                .setHlsVideoSegmentFormat(HlsVideoSegmentFormat.MPEG2_TS)
                .setMetadata(metadata)
                .build();
    }

    @Override public MediaQueueItem toMediaQueueItem(MediaItem item) {
        String url = Objects.requireNonNull(item.localConfiguration).uri.toString();
        CharSequence title = item.mediaMetadata.title;
        return new MediaQueueItem.Builder(mediaInfo(url, title == null ? null : title.toString())).build();
    }

    @Override public MediaItem toMediaItem(MediaQueueItem item) {
        MediaInfo info = Objects.requireNonNull(item.getMedia());
        MediaMetadata metadata = info.getMetadata();
        String url = info.getContentUrl();
        return new MediaItem.Builder()
                .setMediaId(info.getContentId())
                .setUri(url == null ? info.getContentId() : url)
                .setMimeType(info.getContentType())
                .setMediaMetadata(new androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(metadata == null ? null : metadata.getString(MediaMetadata.KEY_TITLE))
                        .build())
                .build();
    }
}
