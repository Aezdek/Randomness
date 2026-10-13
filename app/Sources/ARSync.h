#import <Foundation/Foundation.h>

/// Keeps settings and Saved songs in step with the proxy's website (/admin): when the app opens,
/// comes back to the foreground, every 30 seconds while it's open, and right after Saved changes here.
@interface ARSync : NSObject
+ (instancetype)shared;
- (void)start;
- (void)syncNow;
@end
