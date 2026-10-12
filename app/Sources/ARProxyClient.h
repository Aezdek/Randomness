#import <Foundation/Foundation.h>

@class ARTrack;

/// Talks to aria_proxy.py, which does the YouTube work for the iPod.
@interface ARProxyClient : NSObject

+ (instancetype)shared;

@property (nonatomic, copy) NSString *baseURLString;
@property (nonatomic, copy) NSString *accessKey;
@property (nonatomic, readonly, getter=isConfigured) BOOL configured;

- (NSURL *)streamURLForVideoID:(NSString *)videoID;
- (NSURL *)thumbnailURLForVideoID:(NSString *)videoID large:(BOOL)large;

- (NSURLSessionDataTask *)search:(NSString *)query completion:(void (^)(NSArray<ARTrack *> *results, NSError *error))completion;
/// Clean title, artist and album, whether album art exists, and the segments to skip.
- (void)trackInfoForVideoID:(NSString *)videoID completion:(void (^)(NSDictionary *info, NSError *error))completion;
- (void)pingWithCompletion:(void (^)(NSDictionary *info, NSError *error))completion;

@end
