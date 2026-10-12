#import "ARIcons.h"

#define P(x, y) CGPointMake((x) * s, (y) * s)

@implementation ARIcons

+ (UIImage *)icon:(ARIcon)icon size:(CGFloat)size {
    return [[self icon:icon size:size color:[UIColor blackColor]] imageWithRenderingMode:UIImageRenderingModeAlwaysTemplate];
}

+ (UIImage *)icon:(ARIcon)icon size:(CGFloat)size color:(UIColor *)color {
    static NSCache *cache;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ cache = [NSCache new]; });
    NSString *key = [NSString stringWithFormat:@"%ld/%.1f/%@", (long)icon, size, color];
    UIImage *cached = [cache objectForKey:key];
    if (cached) return cached;

    UIGraphicsBeginImageContextWithOptions(CGSizeMake(size, size), NO, 0);
    [color setFill];
    [color setStroke];
    [self draw:icon size:size color:color];
    UIImage *image = UIGraphicsGetImageFromCurrentImageContext();
    UIGraphicsEndImageContext();
    [cache setObject:image forKey:key];
    return image;
}

+ (UIBezierPath *)path:(CGFloat)lineWidth {
    UIBezierPath *p = [UIBezierPath bezierPath];
    p.lineWidth = lineWidth;
    p.lineCapStyle = kCGLineCapRound;
    p.lineJoinStyle = kCGLineJoinRound;
    return p;
}

+ (void)fillTriangle:(CGFloat)s a:(CGPoint)a b:(CGPoint)b c:(CGPoint)c {
    UIBezierPath *p = [self path:s * 0.05];
    [p moveToPoint:a];
    [p addLineToPoint:b];
    [p addLineToPoint:c];
    [p closePath];
    [p fill];
    [p stroke];
}

+ (void)fillCircle:(CGPoint)center radius:(CGFloat)radius {
    [[UIBezierPath bezierPathWithArcCenter:center radius:radius startAngle:0 endAngle:M_PI * 2 clockwise:YES] fill];
}

+ (void)draw:(ARIcon)icon size:(CGFloat)s color:(UIColor *)color {
    CGFloat lw = MAX(1.5, s * 0.08);
    UIBezierPath *p = [self path:lw];
    switch (icon) {
        case ARIconPlay:
            [self fillTriangle:s a:P(0.27, 0.15) b:P(0.85, 0.5) c:P(0.27, 0.85)];
            break;
        case ARIconPause:
            [[UIBezierPath bezierPathWithRoundedRect:CGRectMake(0.22 * s, 0.15 * s, 0.19 * s, 0.7 * s) cornerRadius:0.04 * s] fill];
            [[UIBezierPath bezierPathWithRoundedRect:CGRectMake(0.59 * s, 0.15 * s, 0.19 * s, 0.7 * s) cornerRadius:0.04 * s] fill];
            break;
        case ARIconNext:
            [self fillTriangle:s a:P(0.08, 0.22) b:P(0.5, 0.5) c:P(0.08, 0.78)];
            [self fillTriangle:s a:P(0.5, 0.22) b:P(0.92, 0.5) c:P(0.5, 0.78)];
            break;
        case ARIconPrevious:
            [self fillTriangle:s a:P(0.92, 0.22) b:P(0.5, 0.5) c:P(0.92, 0.78)];
            [self fillTriangle:s a:P(0.5, 0.22) b:P(0.08, 0.5) c:P(0.5, 0.78)];
            break;
        case ARIconShuffle:
            [p moveToPoint:P(0.1, 0.28)];
            [p addLineToPoint:P(0.28, 0.28)];
            [p addCurveToPoint:P(0.66, 0.72) controlPoint1:P(0.5, 0.28) controlPoint2:P(0.45, 0.72)];
            [p addLineToPoint:P(0.86, 0.72)];
            [p moveToPoint:P(0.1, 0.72)];
            [p addLineToPoint:P(0.28, 0.72)];
            [p addCurveToPoint:P(0.66, 0.28) controlPoint1:P(0.5, 0.72) controlPoint2:P(0.45, 0.28)];
            [p addLineToPoint:P(0.86, 0.28)];
            [p moveToPoint:P(0.76, 0.18)];
            [p addLineToPoint:P(0.87, 0.28)];
            [p addLineToPoint:P(0.76, 0.38)];
            [p moveToPoint:P(0.76, 0.62)];
            [p addLineToPoint:P(0.87, 0.72)];
            [p addLineToPoint:P(0.76, 0.82)];
            [p stroke];
            break;
        case ARIconRepeat:
        case ARIconRepeatOne:
            [p moveToPoint:P(0.14, 0.56)];
            [p addLineToPoint:P(0.14, 0.45)];
            [p addQuadCurveToPoint:P(0.3, 0.29) controlPoint:P(0.14, 0.29)];
            [p addLineToPoint:P(0.84, 0.29)];
            [p moveToPoint:P(0.74, 0.19)];
            [p addLineToPoint:P(0.85, 0.29)];
            [p addLineToPoint:P(0.74, 0.39)];
            [p moveToPoint:P(0.86, 0.44)];
            [p addLineToPoint:P(0.86, 0.55)];
            [p addQuadCurveToPoint:P(0.7, 0.71) controlPoint:P(0.86, 0.71)];
            [p addLineToPoint:P(0.16, 0.71)];
            [p moveToPoint:P(0.26, 0.61)];
            [p addLineToPoint:P(0.15, 0.71)];
            [p addLineToPoint:P(0.26, 0.81)];
            [p stroke];
            if (icon == ARIconRepeatOne) {
                NSDictionary *attrs = @{NSFontAttributeName: [UIFont systemFontOfSize:s * 0.3 weight:UIFontWeightBold],
                                        NSForegroundColorAttributeName: color};
                CGSize t = [@"1" sizeWithAttributes:attrs];
                [@"1" drawAtPoint:CGPointMake((s - t.width) / 2, (s - t.height) / 2) withAttributes:attrs];
            }
            break;
        case ARIconQueue:
            for (int i = 0; i < 3; i++) {
                CGFloat y = 0.28 + i * 0.22;
                [p moveToPoint:P(0.34, y)];
                [p addLineToPoint:P(0.88, y)];
                [self fillCircle:P(0.15, y) radius:s * 0.06];
            }
            [p stroke];
            break;
        case ARIconHeart:
        case ARIconHeartFilled: {
            UIBezierPath *h = [self path:lw];
            [h moveToPoint:P(0.5, 0.86)];
            [h addCurveToPoint:P(0.08, 0.38) controlPoint1:P(0.24, 0.68) controlPoint2:P(0.08, 0.55)];
            [h addCurveToPoint:P(0.5, 0.25) controlPoint1:P(0.08, 0.12) controlPoint2:P(0.4, 0.06)];
            [h addCurveToPoint:P(0.92, 0.38) controlPoint1:P(0.6, 0.06) controlPoint2:P(0.92, 0.12)];
            [h addCurveToPoint:P(0.5, 0.86) controlPoint1:P(0.92, 0.55) controlPoint2:P(0.76, 0.68)];
            [h closePath];
            if (icon == ARIconHeartFilled) [h fill];
            [h stroke];
            break;
        }
        case ARIconChevronDown:
            p.lineWidth = s * 0.1;
            [p moveToPoint:P(0.2, 0.38)];
            [p addLineToPoint:P(0.5, 0.62)];
            [p addLineToPoint:P(0.8, 0.38)];
            [p stroke];
            break;
        case ARIconPlus:
            [p moveToPoint:P(0.5, 0.18)];
            [p addLineToPoint:P(0.5, 0.82)];
            [p moveToPoint:P(0.18, 0.5)];
            [p addLineToPoint:P(0.82, 0.5)];
            [p stroke];
            break;
        case ARIconNote:
            [self fillCircle:P(0.3, 0.76) radius:s * 0.13];
            [self fillCircle:P(0.71, 0.66) radius:s * 0.13];
            [p moveToPoint:P(0.42, 0.76)];
            [p addLineToPoint:P(0.42, 0.2)];
            [p addLineToPoint:P(0.83, 0.1)];
            [p addLineToPoint:P(0.83, 0.66)];
            [p moveToPoint:P(0.42, 0.33)];
            [p addLineToPoint:P(0.83, 0.23)];
            [p stroke];
            break;
        case ARIconStream: {
            UIBezierPath *box = [UIBezierPath bezierPathWithRoundedRect:CGRectMake(0.08 * s, 0.2 * s, 0.84 * s, 0.6 * s) cornerRadius:0.17 * s];
            box.lineWidth = lw;
            [box stroke];
            [self fillTriangle:s a:P(0.42, 0.37) b:P(0.64, 0.5) c:P(0.42, 0.63)];
            break;
        }
        case ARIconSettings: {
            CGFloat knobs[3] = {0.66, 0.34, 0.58};
            for (int i = 0; i < 3; i++) {
                CGFloat y = 0.24 + i * 0.26;
                [p moveToPoint:P(0.1, y)];
                [p addLineToPoint:P(0.9, y)];
            }
            [p stroke];
            for (int i = 0; i < 3; i++) [self fillCircle:P(knobs[i], 0.24 + i * 0.26) radius:s * 0.11];
            break;
        }
        case ARIconSearch:
            [p appendPath:[UIBezierPath bezierPathWithArcCenter:P(0.42, 0.42) radius:s * 0.27 startAngle:0 endAngle:M_PI * 2 clockwise:YES]];
            [p moveToPoint:P(0.62, 0.62)];
            [p addLineToPoint:P(0.86, 0.86)];
            [p stroke];
            break;
        case ARIconMore:
            [self fillCircle:P(0.2, 0.5) radius:s * 0.075];
            [self fillCircle:P(0.5, 0.5) radius:s * 0.075];
            [self fillCircle:P(0.8, 0.5) radius:s * 0.075];
            break;
        case ARIconSpeakerLow:
        case ARIconSpeakerHigh: {
            UIBezierPath *body = [self path:s * 0.04];
            [body moveToPoint:P(0.1, 0.38)];
            [body addLineToPoint:P(0.26, 0.38)];
            [body addLineToPoint:P(0.46, 0.2)];
            [body addLineToPoint:P(0.46, 0.8)];
            [body addLineToPoint:P(0.26, 0.62)];
            [body addLineToPoint:P(0.1, 0.62)];
            [body closePath];
            [body fill];
            [body stroke];
            p.lineWidth = s * 0.07;
            [p addArcWithCenter:P(0.46, 0.5) radius:s * 0.17 startAngle:-0.8 endAngle:0.8 clockwise:YES];
            if (icon == ARIconSpeakerHigh) {
                [p moveToPoint:CGPointMake(0.46 * s + cos(-0.85) * s * 0.33, 0.5 * s + sin(-0.85) * s * 0.33)];
                [p addArcWithCenter:P(0.46, 0.5) radius:s * 0.33 startAngle:-0.85 endAngle:0.85 clockwise:YES];
            }
            [p stroke];
            break;
        }
        case ARIconAlbum: {
            UIBezierPath *disc = [UIBezierPath bezierPathWithArcCenter:P(0.5, 0.5) radius:s * 0.38 startAngle:0 endAngle:M_PI * 2 clockwise:YES];
            disc.lineWidth = lw;
            [disc stroke];
            [self fillCircle:P(0.5, 0.5) radius:s * 0.1];
            break;
        }
    }
}

@end
