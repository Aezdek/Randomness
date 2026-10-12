#import <UIKit/UIKit.h>

/// The bar above the tabs that shows what's playing. Tap it to open Now Playing.
@interface ARMiniPlayerView : UIView
@property (nonatomic, copy) void (^onOpen)(void);
+ (CGFloat)height;
@end
