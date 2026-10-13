#import <Foundation/Foundation.h>
#import <MediaPlayer/MediaPlayer.h>

typedef NS_ENUM(NSInteger, ARTrackSource) {
    ARTrackSourceLocal = 0,
    ARTrackSourceYouTube = 1,
};

@interface ARTrack : NSObject

@property (nonatomic) ARTrackSource source;
/// The persistent ID of a local song, or the YouTube video id.
@property (nonatomic, copy) NSString *identifier;
@property (nonatomic, copy) NSString *title;
@property (nonatomic, copy) NSString *artist;
@property (nonatomic, copy) NSString *album;
@property (nonatomic) NSTimeInterval duration;
@property (nonatomic, strong) NSURL *assetURL;
@property (nonatomic, strong) MPMediaItem *mediaItem;
/// [[start, end], ...] in seconds: non-music parts of a YouTube video to skip.
@property (nonatomic, copy) NSArray<NSArray<NSNumber *> *> *skipSegments;
/// Square album art found for a YouTube track (600x600), when there is some.
@property (nonatomic, copy) NSString *artworkURLString;

+ (instancetype)trackWithMediaItem:(MPMediaItem *)item;
+ (instancetype)trackWithDictionary:(NSDictionary *)dictionary;
- (NSDictionary *)dictionaryRepresentation;

/// Songs that are DRM protected or only in iCloud have no asset URL and can't be played.
@property (nonatomic, readonly, getter=isPlayable) BOOL playable;
@property (nonatomic, readonly) NSString *subtitle;
- (BOOL)isSameTrack:(ARTrack *)other;

@end
