#import "ARArtwork.h"
#import "ARTrack.h"
#import "ARProxyClient.h"

@implementation ARArtwork

+ (NSCache *)cache {
    static NSCache *cache;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        cache = [NSCache new];
        cache.countLimit = 200;
    });
    return cache;
}

+ (dispatch_queue_t)queue {
    static dispatch_queue_t queue;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ queue = dispatch_queue_create("aria.artwork", DISPATCH_QUEUE_SERIAL); });
    return queue;
}

+ (BOOL)isLarge:(CGFloat)size { return size > 90; }

+ (NSString *)keyForTrack:(ARTrack *)track size:(CGFloat)size {
    return [NSString stringWithFormat:@"%ld:%@:%@", (long)track.source, track.identifier, [self isLarge:size] ? @"L" : @"S"];
}

+ (UIImage *)cachedArtworkForTrack:(ARTrack *)track size:(CGFloat)size {
    if (!track) return nil;
    return [[self cache] objectForKey:[self keyForTrack:track size:size]];
}

/// Crops a center square and scales it to the size it will be shown at. YouTube's 4:3 thumbnails
/// have black bars above and below the 16:9 frame, so those are cut away first.
+ (UIImage *)squareImage:(UIImage *)image pixels:(CGFloat)pixels {
    CGImageRef cg = image.CGImage;
    if (!cg) return nil;
    CGFloat w = CGImageGetWidth(cg), h = CGImageGetHeight(cg);
    CGRect content = CGRectMake(0, 0, w, h);
    if (fabs(w / h - 4.0 / 3.0) < 0.02) {
        CGFloat ch = w * 9.0 / 16.0;
        content = CGRectMake(0, (h - ch) / 2, w, ch);
    }
    CGFloat side = MIN(content.size.width, content.size.height);
    CGRect crop = CGRectIntegral(CGRectMake(CGRectGetMidX(content) - side / 2, CGRectGetMidY(content) - side / 2, side, side));
    CGImageRef cropped = CGImageCreateWithImageInRect(cg, crop);
    if (!cropped) return nil;
    CGFloat out = MIN(pixels, side);
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(out, out), YES, 1);
    [[UIImage imageWithCGImage:cropped] drawInRect:CGRectMake(0, 0, out, out)];
    UIImage *result = UIGraphicsGetImageFromCurrentImageContext();
    UIGraphicsEndImageContext();
    CGImageRelease(cropped);
    return [UIImage imageWithCGImage:result.CGImage scale:[UIScreen mainScreen].scale orientation:UIImageOrientationUp];
}

+ (void)loadArtworkForTrack:(ARTrack *)track size:(CGFloat)size completion:(void (^)(UIImage *))completion {
    if (!track) return completion(nil);
    NSString *key = [self keyForTrack:track size:size];
    UIImage *cached = [[self cache] objectForKey:key];
    if (cached) return completion(cached);

    BOOL large = [self isLarge:size];
    CGFloat pixels = (large ? 320 : 96) * [UIScreen mainScreen].scale;
    void (^finish)(UIImage *) = ^(UIImage *image) {
        if (image) [[self cache] setObject:image forKey:key];
        dispatch_async(dispatch_get_main_queue(), ^{ completion(image); });
    };

    if (track.source == ARTrackSourceLocal) {
        MPMediaItem *item = track.mediaItem;
        dispatch_async([self queue], ^{
            MPMediaItemArtwork *art = item.artwork;
            CGFloat points = pixels / [UIScreen mainScreen].scale;
            UIImage *raw = [art imageWithSize:CGSizeMake(points, points)];
            finish(raw ? [self squareImage:raw pixels:pixels] : nil);
        });
        return;
    }

    NSURL *url = [[ARProxyClient shared] thumbnailURLForVideoID:track.identifier large:large];
    if (!url) return completion(nil);
    [[[NSURLSession sharedSession] dataTaskWithURL:url completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
        NSInteger status = [response isKindOfClass:[NSHTTPURLResponse class]] ? ((NSHTTPURLResponse *)response).statusCode : 0;
        UIImage *raw = (status == 200 && data.length) ? [UIImage imageWithData:data] : nil;
        finish(raw ? [self squareImage:raw pixels:pixels] : nil);
    }] resume];
}

@end
