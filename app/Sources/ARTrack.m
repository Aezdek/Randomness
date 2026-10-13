#import "ARTrack.h"

@implementation ARTrack

+ (instancetype)trackWithMediaItem:(MPMediaItem *)item {
    ARTrack *t = [ARTrack new];
    t.source = ARTrackSourceLocal;
    t.identifier = [NSString stringWithFormat:@"%llu", item.persistentID];
    t.title = item.title.length ? item.title : @"Unknown Title";
    t.artist = item.artist ?: item.albumArtist ?: @"";
    t.album = item.albumTitle ?: @"";
    t.duration = item.playbackDuration;
    t.assetURL = item.assetURL;
    t.mediaItem = item;
    return t;
}

+ (instancetype)trackWithDictionary:(NSDictionary *)d {
    NSString *vid = d[@"id"];
    if (![vid isKindOfClass:[NSString class]] || vid.length == 0) return nil;
    ARTrack *t = [ARTrack new];
    t.source = ARTrackSourceYouTube;
    t.identifier = vid;
    t.title = [d[@"title"] isKindOfClass:[NSString class]] ? d[@"title"] : @"Untitled";
    t.artist = [d[@"artist"] isKindOfClass:[NSString class]] ? d[@"artist"] : @"";
    t.duration = [d[@"duration"] respondsToSelector:@selector(doubleValue)] ? [d[@"duration"] doubleValue] : 0;
    t.album = [d[@"album"] isKindOfClass:[NSString class]] ? d[@"album"] : @"";
    t.artworkURLString = [d[@"artwork"] isKindOfClass:[NSString class]] ? d[@"artwork"] : nil;
    return t;
}

- (NSDictionary *)dictionaryRepresentation {
    NSMutableDictionary *d = [NSMutableDictionary dictionaryWithDictionary:@{@"id": self.identifier ?: @"",
                                                                             @"title": self.title ?: @"",
                                                                             @"artist": self.artist ?: @"",
                                                                             @"duration": @(self.duration)}];
    if (self.album.length) d[@"album"] = self.album;
    if (self.artworkURLString.length) d[@"artwork"] = self.artworkURLString;
    return d;
}

- (BOOL)isPlayable {
    return self.source == ARTrackSourceYouTube || self.assetURL != nil;
}

- (NSString *)subtitle {
    if (self.source == ARTrackSourceLocal && self.album.length && self.artist.length) {
        return [NSString stringWithFormat:@"%@ · %@", self.artist, self.album];
    }
    if (self.artist.length) return self.artist;
    return self.source == ARTrackSourceYouTube ? @"YouTube" : @"Unknown Artist";
}

- (BOOL)isSameTrack:(ARTrack *)other {
    return other && other.source == self.source && [other.identifier isEqualToString:self.identifier];
}

@end
