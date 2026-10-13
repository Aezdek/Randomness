#import <UIKit/UIKit.h>

@interface ARTheme : NSObject

+ (UIColor *)background;
+ (UIColor *)surface;
+ (UIColor *)surfaceHighlight;
+ (UIColor *)separator;
+ (UIColor *)accent;
+ (UIColor *)textPrimary;
+ (UIColor *)textSecondary;

+ (UIFont *)fontOfSize:(CGFloat)size weight:(CGFloat)weight;
+ (UIFont *)digitsFontOfSize:(CGFloat)size weight:(CGFloat)weight;

+ (UIImage *)placeholderArtworkOfSize:(CGFloat)size;
+ (UIImage *)pillImageWithColor:(UIColor *)color height:(CGFloat)height;
+ (UIImage *)circleImageWithColor:(UIColor *)color diameter:(CGFloat)diameter;

+ (NSString *)formatTime:(NSTimeInterval)seconds;
/// The corner radius for artwork of this size: rounded, or nearly square if chosen on the website.
+ (CGFloat)artworkCornerRadius:(CGFloat)rounded;
+ (void)applyAppearance;

@end
