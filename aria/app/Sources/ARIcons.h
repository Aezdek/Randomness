#import <UIKit/UIKit.h>

typedef NS_ENUM(NSInteger, ARIcon) {
    ARIconPlay,
    ARIconPause,
    ARIconNext,
    ARIconPrevious,
    ARIconShuffle,
    ARIconRepeat,
    ARIconRepeatOne,
    ARIconQueue,
    ARIconHeart,
    ARIconHeartFilled,
    ARIconChevronDown,
    ARIconPlus,
    ARIconNote,
    ARIconStream,
    ARIconSettings,
    ARIconSearch,
    ARIconMore,
    ARIconSpeakerLow,
    ARIconSpeakerHigh,
    ARIconAlbum,
};

/// Icons drawn in code, so there are no image assets to keep in sync and they stay sharp at any size.
@interface ARIcons : NSObject
/// A template image that takes the tint color of the view showing it.
+ (UIImage *)icon:(ARIcon)icon size:(CGFloat)size;
+ (UIImage *)icon:(ARIcon)icon size:(CGFloat)size color:(UIColor *)color;
@end
