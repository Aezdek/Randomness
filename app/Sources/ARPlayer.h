#import <Foundation/Foundation.h>

@class ARTrack;

typedef NS_ENUM(NSInteger, ARRepeatMode) {
    ARRepeatOff,
    ARRepeatAll,
    ARRepeatOne,
};

/// Posted when the track, the play state, the queue or the modes change.
extern NSString *const ARPlayerDidChangeNotification;
/// Posted about twice a second while time moves.
extern NSString *const ARPlayerTimeNotification;

/// One AVPlayer for both local songs and YouTube streams, with a queue, shuffle, repeat,
/// lock screen info and remote controls.
@interface ARPlayer : NSObject

+ (instancetype)shared;

@property (nonatomic, readonly) NSArray<ARTrack *> *queue;
@property (nonatomic, readonly) NSInteger index;
@property (nonatomic, readonly) ARTrack *currentTrack;
/// Whether the user wants music playing. Stays YES while a stream buffers.
@property (nonatomic, readonly, getter=isPlaying) BOOL playing;
@property (nonatomic, readonly, getter=isBuffering) BOOL buffering;
@property (nonatomic, readonly) NSTimeInterval currentTime;
@property (nonatomic, readonly) NSTimeInterval duration;
@property (nonatomic, readonly) NSString *errorMessage;
/// Goes up when the current track's title or artwork changes, so views know to reload artwork.
@property (nonatomic, readonly) NSUInteger metadataVersion;
/// Skip the non-music parts of YouTube videos (from SponsorBlock). On by default.
@property (nonatomic) BOOL skipsNonMusic;
@property (nonatomic) BOOL shuffle;
@property (nonatomic) ARRepeatMode repeatMode;

- (void)playTracks:(NSArray<ARTrack *> *)tracks startIndex:(NSInteger)index;
- (void)playNext:(ARTrack *)track;
- (void)enqueue:(ARTrack *)track;
- (void)jumpToIndex:(NSInteger)index;
- (void)removeTrackAtIndex:(NSInteger)index;

- (void)play;
- (void)pause;
- (void)togglePlayPause;
- (void)next;
- (void)previous;
- (void)seekTo:(NSTimeInterval)time;

@end
