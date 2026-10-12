#import <UIKit/UIKit.h>

@class ARTrack;

@interface ARArtwork : NSObject
+ (UIImage *)cachedArtworkForTrack:(ARTrack *)track size:(CGFloat)size;
/// Calls back on the main queue with a square image, or nil when the track has no artwork.
+ (void)loadArtworkForTrack:(ARTrack *)track size:(CGFloat)size completion:(void (^)(UIImage *image))completion;
@end
