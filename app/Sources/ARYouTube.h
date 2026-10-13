#import <Foundation/Foundation.h>

@class ARTrack;

/// Gets YouTube music for the player, from YouTube itself (direct mode) or through the proxy,
/// depending on the Streaming mode setting. Auto tries direct first and falls back to the proxy.
@interface ARYouTube : NSObject

+ (void)search:(NSString *)query completion:(void (^)(NSArray<ARTrack *> *results, NSError *error))completion;

/// The audio stream to play, with any HTTP headers it needs. viaProxy says where it came from.
+ (void)streamForTrack:(ARTrack *)track
            forceProxy:(BOOL)forceProxy
            completion:(void (^)(NSURL *url, NSDictionary *headers, BOOL viaProxy, NSError *error))completion;
/// Whether a failed direct stream can be retried through the proxy.
+ (BOOL)canFallBackToProxy;

/// Clean title, artist, album, album art and segments to skip, like the proxy's /api/track.
/// Keys: title, artist, album, artworkURL (string, optional), skip ([[start, end]]).
+ (void)infoForTrack:(ARTrack *)track completion:(void (^)(NSDictionary *info))completion;

/// (song, artist) from a video title and channel, e.g. "Artist - Song (Official Video)".
+ (NSArray<NSString *> *)guessSongAndArtistFromTitle:(NSString *)title channel:(NSString *)channel;

@end
