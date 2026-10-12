#import "ARProxyClient.h"
#import "ARTrack.h"

static NSString *const ARBaseURLKey = @"ARProxyBaseURL";
static NSString *const ARAccessKeyKey = @"ARProxyAccessKey";

@implementation ARProxyClient

+ (instancetype)shared {
    static ARProxyClient *client;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ client = [ARProxyClient new]; });
    return client;
}

- (NSString *)baseURLString {
    return [[NSUserDefaults standardUserDefaults] stringForKey:ARBaseURLKey] ?: @"";
}

- (void)setBaseURLString:(NSString *)value {
    NSString *s = [value stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
    if (s.length && [s rangeOfString:@"://"].location == NSNotFound) s = [@"http://" stringByAppendingString:s];
    while ([s hasSuffix:@"/"]) s = [s substringToIndex:s.length - 1];
    [[NSUserDefaults standardUserDefaults] setObject:s forKey:ARBaseURLKey];
}

- (NSString *)accessKey {
    return [[NSUserDefaults standardUserDefaults] stringForKey:ARAccessKeyKey] ?: @"";
}

- (void)setAccessKey:(NSString *)value {
    NSString *s = [value stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
    [[NSUserDefaults standardUserDefaults] setObject:s forKey:ARAccessKeyKey];
}

- (BOOL)isConfigured {
    return [self URLForPath:@"/api/ping" query:nil] != nil;
}

- (NSURL *)URLForPath:(NSString *)path query:(NSDictionary *)query {
    NSString *base = self.baseURLString;
    if (base.length == 0) return nil;
    NSURLComponents *c = [NSURLComponents componentsWithString:[base stringByAppendingString:path]];
    if (!c.host.length) return nil;
    NSMutableArray *items = [NSMutableArray array];
    [query enumerateKeysAndObjectsUsingBlock:^(NSString *k, NSString *v, BOOL *stop) {
        [items addObject:[NSURLQueryItem queryItemWithName:k value:v]];
    }];
    if (self.accessKey.length) [items addObject:[NSURLQueryItem queryItemWithName:@"key" value:self.accessKey]];
    c.queryItems = items.count ? items : nil;
    return c.URL;
}

- (NSURL *)streamURLForVideoID:(NSString *)videoID {
    return [self URLForPath:[NSString stringWithFormat:@"/stream/%@.m4a", videoID] query:nil];
}

- (NSURL *)thumbnailURLForVideoID:(NSString *)videoID large:(BOOL)large {
    return [self URLForPath:[NSString stringWithFormat:@"/thumb/%@.jpg", videoID] query:@{@"size": large ? @"large" : @"small"}];
}

- (NSError *)errorWithMessage:(NSString *)message {
    return [NSError errorWithDomain:@"ARProxy" code:1 userInfo:@{NSLocalizedDescriptionKey: message}];
}

- (NSURLSessionDataTask *)getJSON:(NSURL *)url timeout:(NSTimeInterval)timeout completion:(void (^)(NSDictionary *, NSError *))completion {
    if (!url) {
        completion(nil, [self errorWithMessage:@"Set your proxy server in Settings first."]);
        return nil;
    }
    NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:url cachePolicy:NSURLRequestReloadIgnoringLocalCacheData timeoutInterval:timeout];
    NSURLSessionDataTask *task = [[NSURLSession sharedSession] dataTaskWithRequest:request completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
        NSDictionary *json = nil;
        if (data.length) {
            id parsed = [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
            if ([parsed isKindOfClass:[NSDictionary class]]) json = parsed;
        }
        if (!error && [json[@"error"] isKindOfClass:[NSString class]]) error = [self errorWithMessage:json[@"error"]];
        if (!error && !json) error = [self errorWithMessage:@"The server sent a response Aria doesn't understand."];
        if (error.code == NSURLErrorCancelled && [error.domain isEqualToString:NSURLErrorDomain]) return;
        dispatch_async(dispatch_get_main_queue(), ^{ completion(error ? nil : json, error); });
    }];
    [task resume];
    return task;
}

- (NSURLSessionDataTask *)search:(NSString *)query completion:(void (^)(NSArray<ARTrack *> *, NSError *))completion {
    NSURL *url = [self URLForPath:@"/api/search" query:@{@"q": query, @"limit": @"25"}];
    return [self getJSON:url timeout:30 completion:^(NSDictionary *json, NSError *error) {
        if (error) return completion(nil, error);
        NSMutableArray *tracks = [NSMutableArray array];
        for (NSDictionary *d in json[@"results"]) {
            if (![d isKindOfClass:[NSDictionary class]]) continue;
            ARTrack *t = [ARTrack trackWithDictionary:d];
            if (t) [tracks addObject:t];
        }
        completion(tracks, nil);
    }];
}

- (void)pingWithCompletion:(void (^)(NSDictionary *, NSError *))completion {
    [self getJSON:[self URLForPath:@"/api/ping" query:nil] timeout:8 completion:completion];
}

@end
