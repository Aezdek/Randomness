#import "ARTheme.h"
#import "ARIcons.h"
#import "ARSettings.h"

static UIColor *ARHex(uint32_t hex) {
    return [UIColor colorWithRed:((hex >> 16) & 0xFF) / 255.0
                           green:((hex >> 8) & 0xFF) / 255.0
                            blue:(hex & 0xFF) / 255.0
                           alpha:1];
}

@implementation ARTheme

+ (UIColor *)background {
    NSString *theme = [ARSettings shared].theme;
    if ([theme isEqualToString:@"black"]) return ARHex(0x000000);
    if ([theme isEqualToString:@"midnight"]) return ARHex(0x0B1020);
    return ARHex(0x0B0B10);
}

+ (UIColor *)surface {
    NSString *theme = [ARSettings shared].theme;
    if ([theme isEqualToString:@"black"]) return ARHex(0x121214);
    if ([theme isEqualToString:@"midnight"]) return ARHex(0x151C30);
    return ARHex(0x17171F);
}

+ (UIColor *)surfaceHighlight {
    NSString *theme = [ARSettings shared].theme;
    if ([theme isEqualToString:@"black"]) return ARHex(0x1E1E22);
    if ([theme isEqualToString:@"midnight"]) return ARHex(0x212A44);
    return ARHex(0x24242F);
}
+ (UIColor *)separator { return [UIColor colorWithWhite:1 alpha:0.08]; }
+ (UIColor *)accent { return [ARSettings shared].accentColor; }
+ (UIColor *)textPrimary { return ARHex(0xF5F5F7); }
+ (UIColor *)textSecondary { return ARHex(0x8E8E9A); }

+ (UIFont *)fontOfSize:(CGFloat)size weight:(CGFloat)weight {
    return [UIFont systemFontOfSize:size weight:weight];
}

+ (UIFont *)digitsFontOfSize:(CGFloat)size weight:(CGFloat)weight {
    return [UIFont monospacedDigitSystemFontOfSize:size weight:weight];
}

+ (UIImage *)placeholderArtworkOfSize:(CGFloat)size {
    static NSCache *cache;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ cache = [NSCache new]; });
    NSNumber *key = @(size);
    UIImage *cached = [cache objectForKey:key];
    if (cached) return cached;

    UIGraphicsBeginImageContextWithOptions(CGSizeMake(size, size), YES, 0);
    CGContextRef ctx = UIGraphicsGetCurrentContext();
    CGColorSpaceRef space = CGColorSpaceCreateDeviceRGB();
    NSArray *colors = @[(__bridge id)ARHex(0x2E2A3D).CGColor, (__bridge id)ARHex(0x16161E).CGColor];
    CGGradientRef gradient = CGGradientCreateWithColors(space, (__bridge CFArrayRef)colors, NULL);
    CGContextDrawLinearGradient(ctx, gradient, CGPointZero, CGPointMake(size, size), 0);
    CGGradientRelease(gradient);
    CGColorSpaceRelease(space);
    CGFloat iconSize = round(size * 0.42);
    UIImage *note = [ARIcons icon:ARIconNote size:iconSize color:[UIColor colorWithWhite:1 alpha:0.28]];
    [note drawAtPoint:CGPointMake(round((size - iconSize) / 2), round((size - iconSize) / 2))];
    UIImage *image = UIGraphicsGetImageFromCurrentImageContext();
    UIGraphicsEndImageContext();
    [cache setObject:image forKey:key];
    return image;
}

+ (UIImage *)pillImageWithColor:(UIColor *)color height:(CGFloat)height {
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(height + 1, height), NO, 0);
    [color setFill];
    [[UIBezierPath bezierPathWithRoundedRect:CGRectMake(0, 0, height + 1, height) cornerRadius:height / 2] fill];
    UIImage *image = UIGraphicsGetImageFromCurrentImageContext();
    UIGraphicsEndImageContext();
    CGFloat cap = height / 2;
    return [image resizableImageWithCapInsets:UIEdgeInsetsMake(0, cap, 0, cap)];
}

+ (UIImage *)circleImageWithColor:(UIColor *)color diameter:(CGFloat)diameter {
    CGFloat pad = 6; // room for the shadow
    CGFloat side = diameter + pad * 2;
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(side, side), NO, 0);
    CGContextRef ctx = UIGraphicsGetCurrentContext();
    CGContextSetShadowWithColor(ctx, CGSizeMake(0, 1), 3, [UIColor colorWithWhite:0 alpha:0.35].CGColor);
    [color setFill];
    [[UIBezierPath bezierPathWithOvalInRect:CGRectMake(pad, pad, diameter, diameter)] fill];
    UIImage *image = UIGraphicsGetImageFromCurrentImageContext();
    UIGraphicsEndImageContext();
    return image;
}

+ (CGFloat)artworkCornerRadius:(CGFloat)rounded {
    return [ARSettings shared].squareArtwork ? MIN(rounded, 2) : rounded;
}

+ (NSString *)formatTime:(NSTimeInterval)seconds {
    if (isnan(seconds) || isinf(seconds) || seconds < 0) seconds = 0;
    NSInteger total = (NSInteger)floor(seconds);
    NSInteger h = total / 3600, m = (total / 60) % 60, s = total % 60;
    if (h > 0) return [NSString stringWithFormat:@"%ld:%02ld:%02ld", (long)h, (long)m, (long)s];
    return [NSString stringWithFormat:@"%ld:%02ld", (long)m, (long)s];
}

+ (void)applyAppearance {
    UINavigationBar *nav = [UINavigationBar appearance];
    nav.barStyle = UIBarStyleBlack;
    nav.translucent = YES;
    nav.tintColor = [self accent];
    nav.titleTextAttributes = @{NSForegroundColorAttributeName: [self textPrimary],
                                NSFontAttributeName: [self fontOfSize:17 weight:UIFontWeightSemibold]};

    UITabBar *tab = [UITabBar appearance];
    tab.barStyle = UIBarStyleBlack;
    tab.translucent = YES;
    tab.tintColor = [self accent];

    [UITableView appearance].backgroundColor = [self background];
    [UITableView appearance].separatorColor = [self separator];
    [UITableView appearance].sectionIndexColor = [self accent];
    [UITableView appearance].sectionIndexBackgroundColor = [UIColor clearColor];

    UITextField *field = [UITextField appearanceWhenContainedInInstancesOfClasses:@[[UISearchBar class]]];
    field.defaultTextAttributes = @{NSForegroundColorAttributeName: [self textPrimary],
                                    NSFontAttributeName: [self fontOfSize:15 weight:UIFontWeightRegular]};
}

@end
