#import <UIKit/UIKit.h>

@class ARTrack;

@interface ARTrackActions : NSObject
/// Play Next, Add to Queue, and Save for YouTube tracks.
+ (void)presentForTrack:(ARTrack *)track from:(UIViewController *)controller sourceView:(UIView *)sourceView;
/// A short message that floats above the mini player.
+ (void)showToast:(NSString *)message;
@end
