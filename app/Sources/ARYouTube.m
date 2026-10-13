#import "ARYouTube.h"
#import "ARTrack.h"
#import "ARSettings.h"
#import "ARProxyClient.h"

static NSString *const ARWebClientVersion = @"2.20250925.01.00";

@implementation ARYouTube

#pragma mark - Routing

+ (BOOL)useDirect { return [ARSettings shared].mode != ARStreamModeProxy; }

+ (BOOL)canFallBackToProxy {
    return [ARSettings shared].mode == ARStreamModeAuto && [ARProxyClient shared].configured;
}

+ (NSError *)error:(NSString *)message {
    return [NSError errorWithDomain:@"ARYouTube" code:1 userInfo:@{NSLocalizedDescriptionKey: message}];
}

+ (void)search:(NSString *)query completion:(void (^)(NSArray<ARTrack *> *, NSError *))completion {
    if (![self useDirect]) {
        [[ARProxyClient shared] search:query completion:completion];
        return;
    }
    [self directSearch:query completion:^(NSArray<ARTrack *> *results, NSError *error) {
        if (results || ![self canFallBackToProxy]) return completion(results, error);
        [[ARProxyClient shared] search:query completion:completion];
    }];
}

+ (void)streamForTrack:(ARTrack *)track forceProxy:(BOOL)forceProxy
            completion:(void (^)(NSURL *, NSDictionary *, BOOL, NSError *))completion {
    void (^viaProxy)(NSError *) = ^(NSError *directError) {
        NSURL *url = [[ARProxyClient shared] streamURLForVideoID:track.identifier];
        if (url) completion(url, nil, YES, nil);
        else completion(nil, nil, NO, directError ?: [self error:@"Set up the YouTube proxy in Settings, or switch Streaming to Direct."]);
    };
    if (forceProxy || ![self useDirect]) return viaProxy(nil);
    [self resolveDirect:track.identifier completion:^(NSURL *url, NSDictionary *headers, NSError *error) {
        if (url) return completion(url, headers, NO, nil);
        if ([self canFallBackToProxy]) return viaProxy(error);
        completion(nil, nil, NO, error);
    }];
}

#pragma mark - InnerTube (YouTube's app API)

+ (void)innertube:(NSString *)endpoint body:(NSDictionary *)body client:(NSDictionary *)client
       completion:(void (^)(NSDictionary *json, NSError *error))completion {
    NSURL *url = [NSURL URLWithString:[NSString stringWithFormat:@"https://www.youtube.com/youtubei/v1/%@?prettyPrint=false", endpoint]];
    NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:url cachePolicy:NSURLRequestReloadIgnoringLocalCacheData timeoutInterval:15];
    req.HTTPMethod = @"POST";
    [req setValue:@"application/json" forHTTPHeaderField:@"Content-Type"];
    [req setValue:client[@"userAgent"] forHTTPHeaderField:@"User-Agent"];
    [req setValue:[client[@"id"] description] forHTTPHeaderField:@"X-YouTube-Client-Name"];
    [req setValue:client[@"version"] forHTTPHeaderField:@"X-YouTube-Client-Version"];
    [req setValue:@"https://www.youtube.com" forHTTPHeaderField:@"Origin"];

    NSMutableDictionary *ctx = [NSMutableDictionary dictionaryWithDictionary:@{@"clientName": client[@"name"], @"clientVersion": client[@"version"], @"hl": @"en", @"gl": @"US"}];
    if ([client[@"extra"] isKindOfClass:[NSDictionary class]]) [ctx addEntriesFromDictionary:client[@"extra"]];
    NSMutableDictionary *payload = [NSMutableDictionary dictionaryWithDictionary:body];
    payload[@"context"] = @{@"client": ctx};
    req.HTTPBody = [NSJSONSerialization dataWithJSONObject:payload options:0 error:nil];

    [[[NSURLSession sharedSession] dataTaskWithRequest:req completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
        id json = data.length ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
        NSInteger status = [response isKindOfClass:[NSHTTPURLResponse class]] ? ((NSHTTPURLResponse *)response).statusCode : 0;
        if (!error && (status != 200 || ![json isKindOfClass:[NSDictionary class]])) {
            error = [self error:[NSString stringWithFormat:@"YouTube answered %ld", (long)status]];
        }
        completion(error ? nil : json, error);
    }] resume];
}

+ (NSString *)text:(id)node {
    if (![node isKindOfClass:[NSDictionary class]]) return nil;
    if ([node[@"simpleText"] isKindOfClass:[NSString class]]) return node[@"simpleText"];
    NSArray *runs = node[@"runs"];
    if (![runs isKindOfClass:[NSArray class]]) return nil;
    NSMutableString *s = [NSMutableString string];
    for (NSDictionary *run in runs) if ([run isKindOfClass:[NSDictionary class]] && [run[@"text"] isKindOfClass:[NSString class]]) [s appendString:run[@"text"]];
    return s;
}

+ (NSTimeInterval)seconds:(NSString *)clock {
    NSTimeInterval total = 0;
    for (NSString *part in [clock componentsSeparatedByString:@":"]) total = total * 60 + part.doubleValue;
    return total;
}

/// Every videoRenderer in a search response, wherever YouTube nests it.
+ (void)collectVideos:(id)node into:(NSMutableArray *)out {
    if ([node isKindOfClass:[NSDictionary class]]) {
        NSDictionary *video = node[@"videoRenderer"];
        if ([video isKindOfClass:[NSDictionary class]]) [out addObject:video];
        for (id child in [node allValues]) [self collectVideos:child into:out];
    } else if ([node isKindOfClass:[NSArray class]]) {
        for (id child in node) [self collectVideos:child into:out];
    }
}

+ (void)directSearch:(NSString *)query completion:(void (^)(NSArray<ARTrack *> *, NSError *))completion {
    NSDictionary *web = @{@"name": @"WEB", @"id": @1, @"version": ARWebClientVersion,
                          @"userAgent": @"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"};
    // params "EgIQAQ==" limits results to videos
    [self innertube:@"search" body:@{@"query": query, @"params": @"EgIQAQ=="} client:web completion:^(NSDictionary *json, NSError *error) {
        NSMutableArray *videos = [NSMutableArray array];
        [self collectVideos:json into:videos];
        NSMutableArray *tracks = [NSMutableArray array];
        NSMutableSet *seen = [NSMutableSet set];
        for (NSDictionary *v in videos) {
            NSString *vid = v[@"videoId"];
            NSString *length = [self text:v[@"lengthText"]];
            if (![vid isKindOfClass:[NSString class]] || !length.length || [seen containsObject:vid]) continue; // no length: live
            [seen addObject:vid];
            NSString *channel = [self text:v[@"ownerText"]] ?: [self text:v[@"longBylineText"]] ?: @"";
            NSArray *meta = [self guessSongAndArtistFromTitle:[self text:v[@"title"]] ?: @"Untitled" channel:channel];
            ARTrack *t = [ARTrack trackWithDictionary:@{@"id": vid, @"title": meta[0], @"artist": meta[1], @"duration": @([self seconds:length])}];
            if (t) [tracks addObject:t];
            if ((NSInteger)tracks.count >= [ARSettings shared].searchResults) break;
        }
        dispatch_async(dispatch_get_main_queue(), ^{
            if (json && tracks.count == 0 && videos.count == 0) completion(nil, [self error:@"YouTube's search answer has changed. Try Proxy mode."]);
            else completion(json ? tracks : nil, error);
        });
    }];
}

+ (NSCache *)streamCache {
    static NSCache *cache;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ cache = [NSCache new]; });
    return cache;
}

+ (void)resolveDirect:(NSString *)vid completion:(void (^)(NSURL *, NSDictionary *, NSError *))completion {
    NSDictionary *hit = [[self streamCache] objectForKey:vid];
    if (hit && [hit[@"expires"] timeIntervalSinceNow] > 0) return completion(hit[@"url"], hit[@"headers"], nil);
    [self resolve:vid clients:[ARSettings shared].directClients index:0 lastError:nil completion:^(NSURL *url, NSDictionary *headers, NSError *error) {
        if (url) [[self streamCache] setObject:@{@"url": url, @"headers": headers, @"expires": [NSDate dateWithTimeIntervalSinceNow:3600]} forKey:vid];
        dispatch_async(dispatch_get_main_queue(), ^{ completion(url, headers, error); });
    }];
}

/// Tries each app profile in turn until one gives a plain audio URL.
+ (void)resolve:(NSString *)vid clients:(NSArray *)clients index:(NSUInteger)i lastError:(NSError *)lastError
     completion:(void (^)(NSURL *, NSDictionary *, NSError *))completion {
    if (i >= clients.count) return completion(nil, nil, lastError ?: [self error:@"YouTube didn't give a playable stream."]);
    NSDictionary *client = clients[i];
    NSDictionary *body = @{@"videoId": vid, @"contentCheckOk": @YES, @"racyCheckOk": @YES};
    [self innertube:@"player" body:body client:client completion:^(NSDictionary *json, NSError *error) {
        NSDictionary *playability = json[@"playabilityStatus"];
        NSString *status = [playability isKindOfClass:[NSDictionary class]] ? playability[@"status"] : nil;
        if (json && ![status isEqual:@"OK"]) {
            NSString *reason = [playability[@"reason"] isKindOfClass:[NSString class]] ? playability[@"reason"] : status ?: @"unplayable";
            error = [self error:[NSString stringWithFormat:@"YouTube: %@", reason]];
        }
        NSURL *best = nil;
        long bestRate = -1;
        NSArray *formats = [json[@"streamingData"] isKindOfClass:[NSDictionary class]] ? json[@"streamingData"][@"adaptiveFormats"] : nil;
        for (NSDictionary *f in [formats isKindOfClass:[NSArray class]] ? formats : [NSArray array]) {
            if (![f isKindOfClass:[NSDictionary class]] || ![f[@"url"] isKindOfClass:[NSString class]]) continue; // ciphered: skip
            if (![f[@"mimeType"] isKindOfClass:[NSString class]] || ![f[@"mimeType"] hasPrefix:@"audio/mp4"]) continue; // AAC only on iOS 9
            long rate = [f[@"itag"] integerValue] == 140 ? LONG_MAX : [f[@"bitrate"] longValue];
            if (rate > bestRate) { bestRate = rate; best = [NSURL URLWithString:f[@"url"]]; }
        }
        if (best) return completion(best, @{@"User-Agent": client[@"userAgent"]}, nil);
        if (json && !error) error = [self error:@"YouTube gave no stream the iPod can play."];
        [self resolve:vid clients:clients index:i + 1 lastError:error completion:completion];
    }];
}

#pragma mark - Metadata

+ (NSString *)replace:(NSString *)pattern in:(NSString *)text with:(NSString *)with {
    NSRegularExpression *re = [NSRegularExpression regularExpressionWithPattern:pattern options:NSRegularExpressionCaseInsensitive error:nil];
    return [re stringByReplacingMatchesInString:text options:0 range:NSMakeRange(0, text.length) withTemplate:with];
}

+ (NSString *)noisePattern {
    return @"\\s*[\\(\\[\\{][^\\)\\]\\}]*\\b(?:official|lyric|lyrics|audio|video|visuali[sz]er|music video|hd|hq|4k|8k|1080p|720p|mv|m/v|clip officiel|videoclip|video oficial|letra|explicit|clean|full song)\\b[^\\)\\]\\}]*[\\)\\]\\}]";
}

+ (NSString *)trim:(NSString *)s {
    return [s stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
}

+ (NSString *)cleanSong:(NSString *)text {
    text = [self replace:[self noisePattern] in:text ?: @"" with:@""];
    text = [self replace:@"\\s*[|｜/]\\s*.*$" in:text with:@""];
    text = [[self trim:text] stringByTrimmingCharactersInSet:[NSCharacterSet characterSetWithCharactersInString:@"\"“”'"]];
    return [self replace:@"\\s{2,}" in:[self trim:text] with:@" "];
}

+ (NSString *)cleanChannel:(NSString *)channel {
    channel = [self replace:@"\\s*-\\s*Topic$" in:channel ?: @"" with:@""];
    channel = [self replace:@"\\s*vevo$" in:channel with:@""];
    channel = [self replace:@"\\s*official(?:\\s*(?:channel|youtube channel|music))?$" in:channel with:@""];
    return [self trim:channel];
}

+ (NSArray<NSString *> *)guessSongAndArtistFromTitle:(NSString *)title channel:(NSString *)channel {
    title = title ?: @"";
    if ([channel hasSuffix:@" - Topic"]) {
        NSString *song = [self cleanSong:title];
        return @[song.length ? song : title, [self cleanChannel:channel]];
    }
    NSString *stripped = [self trim:[self replace:[self noisePattern] in:title with:@""]];
    NSRegularExpression *quoted = [NSRegularExpression regularExpressionWithPattern:@"^(.+?)\\s+[\"“](.+?)[\"”]$" options:0 error:nil];
    NSTextCheckingResult *q = [quoted firstMatchInString:stripped options:0 range:NSMakeRange(0, stripped.length)];
    if (q) return @[[self cleanSong:[stripped substringWithRange:[q rangeAtIndex:2]]], [self trim:[stripped substringWithRange:[q rangeAtIndex:1]]]];
    NSRegularExpression *dash = [NSRegularExpression regularExpressionWithPattern:@"\\s+[-–—~]\\s+" options:0 error:nil];
    NSTextCheckingResult *d = [dash firstMatchInString:stripped options:0 range:NSMakeRange(0, stripped.length)];
    if (d) {
        NSString *artist = [self trim:[stripped substringToIndex:d.range.location]];
        NSString *song = [self cleanSong:[stripped substringFromIndex:NSMaxRange(d.range)]];
        if (artist.length && song.length) return @[song, [artist stringByTrimmingCharactersInSet:[NSCharacterSet characterSetWithCharactersInString:@"\""]]];
    }
    NSString *song = [self cleanSong:title];
    return @[song.length ? song : title, [self cleanChannel:channel]];
}

+ (NSString *)norm:(NSString *)text {
    text = [[self replace:@"\\s*[\\(\\[].*?[\\)\\]]" in:text ?: @"" with:@""] lowercaseString];
    NSRegularExpression *feat = [NSRegularExpression regularExpressionWithPattern:@"\\s+(?:feat\\.?|ft\\.?|featuring|with|x|&|,)\\s+" options:0 error:nil];
    NSTextCheckingResult *m = [feat firstMatchInString:text options:0 range:NSMakeRange(0, text.length)];
    if (m) text = [text substringToIndex:m.range.location];
    return [self replace:@"[^0-9a-z\\u00c0-\\uffff]+" in:text with:@""];
}

+ (BOOL)similar:(NSString *)a to:(NSString *)b {
    a = [self norm:a]; b = [self norm:b];
    if (!a.length || !b.length) return NO;
    return [a isEqualToString:b] || (MIN(a.length, b.length) >= 4 && ([a rangeOfString:b].location != NSNotFound || [b rangeOfString:a].location != NSNotFound));
}

+ (void)getJSON:(NSString *)urlString completion:(void (^)(id json, NSInteger status))completion {
    NSURL *url = [NSURL URLWithString:urlString];
    if (!url) return completion(nil, 0);
    NSURLRequest *req = [NSURLRequest requestWithURL:url cachePolicy:NSURLRequestUseProtocolCachePolicy timeoutInterval:10];
    [[[NSURLSession sharedSession] dataTaskWithRequest:req completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
        NSInteger status = [response isKindOfClass:[NSHTTPURLResponse class]] ? ((NSHTTPURLResponse *)response).statusCode : 0;
        completion(data.length ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil, status);
    }] resume];
}

+ (NSString *)encode:(NSString *)s {
    // Strict: iOS 9's NSURL rejects raw Unicode, and &, = or + in a song name would break the query.
    NSCharacterSet *safe = [NSCharacterSet characterSetWithCharactersInString:@"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"];
    return [s stringByAddingPercentEncodingWithAllowedCharacters:safe] ?: @"";
}

+ (void)infoForTrack:(ARTrack *)track completion:(void (^)(NSDictionary *))completion {
    if ([ARSettings shared].mode != ARStreamModeDirect && [ARProxyClient shared].configured) {
        // The proxy also knows YouTube's own music data, so it gives the best answer.
        [[ARProxyClient shared] trackInfoForVideoID:track.identifier completion:^(NSDictionary *info, NSError *error) {
            if (info) return completion(info);
            [self deviceInfoForTrack:track completion:completion];
        }];
        return;
    }
    [self deviceInfoForTrack:track completion:completion];
}

+ (void)deviceInfoForTrack:(ARTrack *)track completion:(void (^)(NSDictionary *))completion {
    NSMutableDictionary *info = [NSMutableDictionary dictionaryWithDictionary:@{@"title": track.title ?: @"", @"artist": track.artist ?: @"", @"skip": [NSArray array]}];
    dispatch_group_t group = dispatch_group_create();

    NSString *artist = [[track.artist componentsSeparatedByString:@","] firstObject] ?: @"";
    NSString *term = [self encode:[NSString stringWithFormat:@"%@ %@", artist, track.title ?: @""]];
    NSString *itunes = [NSString stringWithFormat:@"https://itunes.apple.com/search?term=%@&entity=song&limit=10", term];
    dispatch_group_enter(group);
    [self getJSON:itunes completion:^(id json, NSInteger status) {
        NSArray *results = [json isKindOfClass:[NSDictionary class]] ? json[@"results"] : nil;
        for (NSDictionary *r in [results isKindOfClass:[NSArray class]] ? results : [NSArray array]) {
            if (![r isKindOfClass:[NSDictionary class]]) continue;
            if (![self similar:r[@"trackName"] to:track.title] || ![self similar:r[@"artistName"] to:artist]) continue;
            NSTimeInterval length = [r[@"trackTimeMillis"] doubleValue] / 1000;
            if (track.duration > 0 && length > 0 && fabs(length - track.duration) > 15) continue;
            @synchronized (info) {
                if ([r[@"trackName"] isKindOfClass:[NSString class]]) info[@"title"] = r[@"trackName"];
                if ([r[@"artistName"] isKindOfClass:[NSString class]]) info[@"artist"] = r[@"artistName"];
                if ([r[@"collectionName"] isKindOfClass:[NSString class]]) info[@"album"] = r[@"collectionName"];
                if ([r[@"artworkUrl100"] isKindOfClass:[NSString class]]) {
                    info[@"artworkURL"] = [r[@"artworkUrl100"] stringByReplacingOccurrencesOfString:@"100x100bb" withString:@"600x600bb"];
                }
            }
            break;
        }
        dispatch_group_leave(group);
    }];

    NSString *categories = [self encode:@"[\"sponsor\",\"selfpromo\",\"interaction\",\"music_offtopic\"]"];
    NSString *sponsorblock = [NSString stringWithFormat:@"https://sponsor.ajay.app/api/skipSegments?videoID=%@&categories=%@", track.identifier, categories];
    dispatch_group_enter(group);
    [self getJSON:sponsorblock completion:^(id json, NSInteger status) {
        NSMutableArray *skip = [NSMutableArray array];
        for (NSDictionary *s in [json isKindOfClass:[NSArray class]] ? json : [NSArray array]) {
            NSArray *seg = [s isKindOfClass:[NSDictionary class]] ? s[@"segment"] : nil;
            NSString *action = [s isKindOfClass:[NSDictionary class]] ? s[@"actionType"] : nil;
            if (![seg isKindOfClass:[NSArray class]] || seg.count != 2 || (action && ![action isEqual:@"skip"])) continue;
            if ([seg[1] doubleValue] - [seg[0] doubleValue] >= 1) [skip addObject:@[seg[0], seg[1]]];
        }
        @synchronized (info) { info[@"skip"] = skip; }
        dispatch_group_leave(group);
    }];

    dispatch_group_notify(group, dispatch_get_main_queue(), ^{ completion(info); });
}

@end
