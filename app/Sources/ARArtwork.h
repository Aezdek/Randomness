#import <UIKit/UIKit.h>

@class ARTrack;

@interface ARArtwork : NSObject
+ (UIImage *)cachedArtworkForTrack:(ARTrack *)track size:(CGFloat)size;
/// Drops cached artwork, for example when the proxy found album art to replace a video thumbnail.
+ (void)forgetArtworkForTrack:(ARTrack *)track;
/// Calls back on the main queue with a square image, or nil when the track has no artwork.
+ (void)loadArtworkForTrack:(ARTrack *)track size:(CGFloat)size completion:(void (^)(UIImage *image))completion;
@end
